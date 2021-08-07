(ns metabase.driver.bigquery-cloud-sdk-test
  (:require [clojure.test :refer :all]
            [metabase.db.metadata-queries :as metadata-queries]
            [metabase.driver :as driver]
            [metabase.driver.bigquery-cloud-sdk :as bigquery]
            [metabase.models :refer [Field Table]]
            [metabase.query-processor :as qp]
            [metabase.sync :as sync]
            [metabase.test :as mt]
            [metabase.test.data.bigquery-cloud-sdk :as bigquery.tx]
            [metabase.test.util :as tu]
            [metabase.util :as u]))

(deftest table-rows-sample-test
  (mt/test-driver
   :bigquery-cloud-sdk
   (testing "without worrying about pagination"
     (is (= [[1 "Red Medicine"]
             [2 "Stout Burgers & Beers"]
             [3 "The Apple Pan"]
             [4 "Wurstküche"]
             [5 "Brite Spot Family Restaurant"]]
            (->> (metadata-queries/table-rows-sample (Table (mt/id :venues))
                   [(Field (mt/id :venues :id))
                    (Field (mt/id :venues :name))]
                   (constantly conj))
                 (sort-by first)
                 (take 5)))))

   ;; the initial dataset isn't realized until it's used the first time. because of that,
   ;; we don't care how many pages it took to load this dataset above. it will be a large
   ;; number because we're just tracking the number of times `get-query-results` gets invoked.
   (testing "with pagination"
     (let [pages-retrieved (atom 0)
           page-callback   (fn [] (swap! pages-retrieved inc))]
       (with-bindings {#'bigquery/*max-results-per-page*  25
                       #'bigquery/*page-callback*         page-callback
                       ;; for this test, set timeout to 0 to prevent setting it
                       ;; so that the "fast" query path can be used (so that the max-results-per-page actually takes
                       ;; effect); see com.google.cloud.bigquery.QueryRequestInfo.isFastQuerySupported
                       #'bigquery/*query-timeout-seconds* 0}
         (let [actual (->> (metadata-queries/table-rows-sample (Table (mt/id :venues))
                             [(Field (mt/id :venues :id))
                              (Field (mt/id :venues :name))]
                             (constantly conj))
                           (sort-by first)
                           (take 5))]
         (is (= [[1 "Red Medicine"]
                 [2 "Stout Burgers & Beers"]
                 [3 "The Apple Pan"]
                 [4 "Wurstküche"]
                 [5 "Brite Spot Family Restaurant"]]
                actual))
         ;; the `(sort-by)` above will cause the entire resultset to be realized, so
         ;; we want to make sure that it really did retrieve 25 rows per request
         ;; this only works if the timeout has been temporarily set to 0 (see above)
         (is (= 4 @pages-retrieved))))))))

;; These look like the macros from metabase.query-processor-test.expressions-test
;; but conform to bigquery naming rules
(defn- calculate-bird-scarcity* [formula filter-clause]
  (mt/formatted-rows [2.0]
    (mt/dataset daily-bird-counts
      (mt/run-mbql-query bird_count
        {:expressions {"bird_scarcity" formula}
         :fields      [[:expression "bird_scarcity"]]
         :filter      filter-clause
         :order-by    [[:asc $date]]
         :limit       10}))))

(defmacro ^:private calculate-bird-scarcity [formula & [filter-clause]]
  `(mt/dataset ~'daily-bird-counts
     (mt/$ids ~'bird_count
       (calculate-bird-scarcity* ~formula ~filter-clause))))

(deftest nulls-and-zeroes-test
  (mt/test-driver :bigquery-cloud-sdk
    (testing (str "hey... expressions should work if they are just a Field! (Also, this lets us take a peek at the "
                  "raw values being used to calculate the formulas below, so we can tell at a glance if they're right "
                  "without referring to the EDN def)")
      (is (= [[nil] [0.0] [0.0] [10.0] [8.0] [5.0] [5.0] [nil] [0.0] [0.0]]
             (calculate-bird-scarcity $count))))

    (testing (str "do expressions automatically handle division by zero? Should return `nil` in the results for places "
                  "where that was attempted")
      (is (= [[nil] [nil] [10.0] [12.5] [20.0] [20.0] [nil] [nil] [9.09] [7.14]]
             (calculate-bird-scarcity [:/ 100.0 $count]
                                      [:!= $count nil]))))


    (testing (str "do expressions handle division by `nil`? Should return `nil` in the results for places where that "
                  "was attempted")
      (is (= [[nil] [10.0] [12.5] [20.0] [20.0] [nil] [9.09] [7.14] [12.5] [7.14]]
             (calculate-bird-scarcity [:/ 100.0 $count]
                                      [:or
                                       [:= $count nil]
                                       [:!= $count 0]]))))

    (testing "can we handle BOTH NULLS AND ZEROES AT THE SAME TIME????"
      (is (= [[nil] [nil] [nil] [10.0] [12.5] [20.0] [20.0] [nil] [nil] [nil]]
             (calculate-bird-scarcity [:/ 100.0 $count]))))

    (testing "ok, what if we use multiple args to divide, and more than one is zero?"
      (is (= [[nil] [nil] [nil] [1.0] [1.56] [4.0] [4.0] [nil] [nil] [nil]]
             (calculate-bird-scarcity [:/ 100.0 $count $count]))))

    (testing "are nulls/zeroes still handled appropriately when nested inside other expressions?"
      (is (= [[nil] [nil] [nil] [20.0] [25.0] [40.0] [40.0] [nil] [nil] [nil]]
             (calculate-bird-scarcity [:* [:/ 100.0 $count] 2]))))

    (testing (str "if a zero is present in the NUMERATOR we should return ZERO and not NULL "
                  "(`0 / 10 = 0`; `10 / 0 = NULL`, at least as far as MBQL is concerned)")
      (is (= [[nil] [0.0] [0.0] [1.0] [0.8] [0.5] [0.5] [nil] [0.0] [0.0]]
             (calculate-bird-scarcity [:/ $count 10]))))

    (testing "can addition handle nulls & zeroes?"
      (is (= [[nil] [10.0] [10.0] [20.0] [18.0] [15.0] [15.0] [nil] [10.0] [10.0]]
             (calculate-bird-scarcity [:+ $count 10]))))

    (testing "can subtraction handle nulls & zeroes?"
      (is (= [[nil] [10.0] [10.0] [0.0] [2.0] [5.0] [5.0] [nil] [10.0] [10.0]]
             (calculate-bird-scarcity [:- 10 $count]))))


    (testing "can multiplications handle nulls & zeros?"
      (is (= [[nil] [0.0] [0.0] [10.0] [8.0] [5.0] [5.0] [nil] [0.0] [0.0]]
             (calculate-bird-scarcity [:* 1 $count]))))))

(deftest db-timezone-id-test
  (mt/test-driver :bigquery-cloud-sdk
    (is (= "UTC"
           (tu/db-timezone-id)))))

(defn- do-with-view [f]
  (driver/with-driver :bigquery-cloud-sdk
    (let [view-name (name (munge (gensym "view_")))]
      (mt/with-temp-copy-of-db
        (try
          (bigquery.tx/execute!
           (str "CREATE VIEW `v3_test_data.%s` "
                "AS "
                "SELECT v.id AS id, v.name AS venue_name, c.name AS category_name "
                "FROM `%s.v3_test_data.venues` v "
                "LEFT JOIN `%s.v3_test_data.categories` c "
                "ON v.category_id = c.id "
                "ORDER BY v.id ASC "
                "LIMIT 3")
           view-name
           (bigquery.tx/project-id)
           (bigquery.tx/project-id))
          (f view-name)
          (finally
            (bigquery.tx/execute! "DROP VIEW IF EXISTS `v3_test_data.%s`" view-name)))))))

(defmacro ^:private with-view [[view-name-binding] & body]
  `(do-with-view (fn [~(or view-name-binding '_)] ~@body)))

(deftest sync-views-test
  (mt/test-driver :bigquery-cloud-sdk
    (with-view [view-name]
      (is (contains? (:tables (driver/describe-database :bigquery-cloud-sdk (mt/db)))
                     {:schema nil, :name view-name})
          "`describe-database` should see the view")
      (is (= {:schema nil
              :name   view-name
              :fields #{{:name "id", :database-type "INTEGER", :base-type :type/Integer, :database-position 0}
                        {:name "venue_name", :database-type "STRING", :base-type :type/Text, :database-position 1}
                        {:name "category_name", :database-type "STRING", :base-type :type/Text, :database-position 2}}}
             (driver/describe-table :bigquery-cloud-sdk (mt/db) {:name view-name}))
          "`describe-tables` should see the fields in the view")
      (sync/sync-database! (mt/db))
      (testing "We should be able to run queries against the view (#3414)"
        (is (= [[1 "Red Medicine" "Asian" ]
                [2 "Stout Burgers & Beers" "Burger"]
                [3 "The Apple Pan" "Burger"]]
               (mt/rows
                 (mt/run-mbql-query nil
                   {:source-table (mt/id view-name)
                    :order-by     [[:asc (mt/id view-name :id)]]}))))))))

(deftest query-integer-pk-or-fk-test
  (mt/test-driver :bigquery-cloud-sdk
    (testing "We should be able to query a Table that has a :type/Integer column marked as a PK or FK"
      (is (= [["1" "Plato Yeshua" "2014-04-01T08:30:00Z"]]
             (mt/rows (mt/user-http-request :rasta :post 202 "dataset" (mt/mbql-query users {:limit 1, :order-by [[:asc $id]]}))))))))

(deftest return-errors-test
  (mt/test-driver :bigquery-cloud-sdk
    (testing "If a Query fails, we should return the error right away (#14918)"
      (let [before-ms (System/currentTimeMillis)]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Error executing query"
             (qp/process-query
              {:database (mt/id)
               :type     :native
               :native   {:query "SELECT abc FROM 123;"}})))
        (testing "Should return the error *before* the query timeout"
          (let [duration-ms (- (System/currentTimeMillis) before-ms)]
            (is (< duration-ms (u/seconds->ms @#'bigquery/*query-timeout-seconds*)))))))))
