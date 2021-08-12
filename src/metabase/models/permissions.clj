(ns metabase.models.permissions
  "Low-level Metabase permissions system definition and utility functions.

  The Metabase permissions system is based around permissions *paths* that are granted to individual
  [[metabase.models.permissions-group]]s.

  ### Object paths vs. User Paths

  Permissions paths are slash-delimited strings such as `/db/1/schema/PUBLIC/`. Each slash represents a different part
  of the permissions path, and each permissions path must start and end with a slash. Permissions paths are used in
  two different contexts:

  - An [[ObjectPath]] represents some sort of permission that is required in order to perform some *action* on an
    actual *object* (i.e., something represented by a row in the application database), such as
    a [[metabase.models.database]], [[metabase.models.table]], or [[metabase.models.collection]].

  - A [[UserPath]] represents something that a [[metabase.models.permissions-group]] (equivalent to a *role*) can be
    *granted* (given). [[UserPath]] is a superset of [[ObjectPath]]. All [[UserPath]]s can be granted, but not
    all [[ObjectPath]]s make sense as things that can be required in order to perform an action. Some [[UserPath]]s
    serve a subtractive \"anti-permissions\" in the sense that they take away access for things.

    The current User's permissions are automatically bound to [[metabase.api.common/*current-user-permissions-set*]]
    by [[metabase.server.middleware.session/bind-current-user]] for every REST API request, and by others when queries
    are ran in a non-API thread (e.g. for MetaBot or scheduled Dashboard Subscriptions). This *permissions set* is the
    union of all [[UserPath]]s  granted to all the Groups of which that User is a member, and is used pervasively
    throughout Metabase.

  ### The prefix system

  Permissions paths are based on a prefix system -- if the current user has full permissions for Database 1, `/db/1/`,
  then it is implied that they have permissions to *read* Database 1 as well, represented by the [[ObjectPath]]
  `/db/1/read/`. `/db/1/` thus implies permissions for anything starting with `/db/1/`, including `/db/1/read/`.

  This prefix  system allows us  to easily and  efficiently query the application  database to find  relevant matching
  permissions matching an [[ObjectPath]] or [[UserPath]] using `LIKE`; see [[metabase.models.database/pre-delete]] for
  an example of the sort of efficient queries the prefix system facilitates.

  Admins are given permissions for `/`, which means they have permissions to do *anything*, such as `/db/1/read/` (read
  Database 1).

  ### Different types of [[ObjectPath]] (action) permissions

  There are two main types of [[ObjectPath]] permissions:

  * _data permissions_ -- permissions to view, update, or run ad-hoc or SQL queries against a Database or Table.

  * _Collection permissions_ -- permissions to view/curate/etc. an individual [[metabase.models.collection]] and the
    items inside it. Collection permissions apply to individual Collections and to any non-Collection items inside that
    Collection. Child Collections get their own permissions. Many objects such as Cards (a.k.a. *Saved Questions*) and
    Dashboards get their permissions from the Collection in which they live.

  Data permissions and Collection permissions are both grantable, i.e. they can be considered to be
  both [[ObjectPath]]s and [[UserPath]]s.

  ### Different types of [[UserPath]] (grantable) permissions and anti-permissions

  In addition to data permissions and Collection permissions, a User can also be granted three additional types of
  permissions. Some of these types are referred to as \"anti-permissions\" because they are subtractive grants that
  take away permissions from what the User would otherwise have.

  * _root permissions_ -- permissions for `/`, i.e. full access for everything. Automatically granted to
    the [[metabase.models.permissions-group/admin]] \"magic group\" (see [[metabase.models.permissions-group]] for more
    details) that gets created on first launch. All admin users are members of the `admin` group and vice versa.

  * _segmented permissions_ -- a special grant for a Table that applies sandboxing, a.k.a. row-level permissions,
    a.k.a. segmented permissions, to any queries ran by the User when that User does not have full data permissions.
    Segmented permissions allow a User to run ad-hoc MBQL queries against the Table in question; regardless of whether
    they have relevant Collection permissions, queries against the sandboxed Table are rewritten to replace the Table
    itself with a special type of nested query called a
    [[metabase-enterprise.sandbox.models.group-table-access-policy]], or _GTAP_. Note that segmented permissions are
    both additive and subtractive -- they are additive because they grant (sandboxed) ad-hoc query access for a Table,
    but subtractive in that any access thru a Saved Question will now be sandboxed as well.

    Additional things to know:

    * Sandboxes permissions are only available in Metabase® Enterprise Edition™.

    * Only one GTAP may defined per-Group per-Table (this is an application-DB-level constraint). A User may have
      multiple applicable GTAPs if they are members of multiple groups that have sandboxed anti-perms for that Table; in
      that case, the QP signals an error if multiple GTAPs apply to a given Table for the current User (see
      [[metabase-enterprise.sandbox.query-processor.middleware.row-level-restrictions/assert-one-gtap-per-table]]).

    * Segmented (sandboxing) anti-permissions and GTAPs are tied together, and a Group should be given both (or both
      should be deleted) at the same time. This is *not* currently enforced as a hard application DB constraint, but is
      done in the respective Toucan pre-delete actions. The QP will signal an error if the current user has segmented
      permissions but no matching GTAP exists.

    * Segmented permissions can also be used to enforce column-level permissions -- any column not returned by the
      underlying GTAP query is not allowed to be references by the parent query thru other means such as filter clauses.
      See [[metabase-enterprise.sandbox.query-processor.middleware.column-level-perms-check]].

    * GTAPs are not allowed to add columns not present in the original Table, or change their effective type to
      something incompatible (this constraint is in place so we other things continue to work transparently regardless
      of whether the Table is swapped out.)

  * *block anti-permissions* are per-Group, per-Table grants that tell Metabase to disallow running Saved
    Questions unless the User has data permissions (in other words, disregard Collection permissions). See the
    `Determining query permissions` section below for more details. As with segmented anti-permissions, block
    anti-permissions are only available in Metabase® Enterprise Edition™.

  ### Determining CRUD permissions in the REST API

  REST API permissions checks are generally done in various `metabase.api.*` namespaces. Methods for determine whether
  the current User can perform various CRUD actions are defined by
  the [[metabase.models.interface/IObjectPermissions]] protocol; this protocol
  defines [[metabase.models.interface/can-read?]] (in the API sense, not in the run-query sense)
  and [[metabase.models.interface/can-write?]] as well as the newer [[metabase.models.interface/can-create?]] and
  [[metabase.models.interface/can-update?]] methods. Implementations for these methods live in `metabase.model.*`
  namespaces.

  The implementation of these methods is up to individual models. The majority of implementations check whether
  [[metabase.api.common/*current-user-permissions-set*]] includes permissions for a given [[ObjectPath]] (action)
  using [[set-has-full-permissions?]], or for a set of [[ObjectPath]]s using [[set-has-full-permissions-for-set?]].

  Other implementations check whether a user has _partial permissions_ for a path or set
  using [[set-has-partial-permissions?]] or [[set-has-partial-permissions-for-set?]]. Partial permissions means that
  the User has permissions for some subpath of the path in question, e.g. `/db/1/read/` is considered to be partial
  permissions for `/db/1/`. For example the [[metabase.models.interface/can-read?]] implementation for Database checks
  whether the current User has *any* permissions for that Database; a User can fetch Database 1 from API
  endpoints (\"read\" it) if they have any permissions starting with `/db/1/`, for example `/db/1/` itself (full
  permissions), `/db/1/native/` (ad-hoc SQL query permissions), or permissions, or
  `/db/1/schema/PUBLIC/table/2/query/` (run ad-hoc queries against Table 2 permissions).

  See documentation for [[metabase.models.interface/IObjectPermissions]] for more details.

  ### Determining query permissions

  Normally, a User is allowed to view (i.e., run the query for) a Saved Question if they have read permissions for the
  Collection in which Saved Question lives, **or** if they have data permissions for the Database and Table(s) the
  Question accesses. The main idea here is that some Users with more permissions can go create a curated set of Saved
  Questions they deem appropriate for less-privileged Users to see, and put them in a Collection they can see. These
  Users would still be prevented from poking around things on their own, however.

  The Query Processor middleware in [[metabase.query-processor.middleware.permissions]],
  [[metabase-enterprise.sandbox.query-processor.middleware.row-level-restrictions]], and
  [[metabase-enterprise.forthcoming-block-permissions-middleware-namespace]] determines whether the current
  User has permissions to run the current query. Permissions are as follows:

  | Data perms? | Coll perms? | Block? | Segmented? | Can run? |
  | ----------- | ----------- | ------ | ---------- | -------- |
  |          ⛔ |          ⛔ |     ⛔ |         ⛔ |        ⛔ |
  |          ⛔ |          ⛔ |     ⛔ |         ✅ |        ⚠ |
  |          ⛔ |          ⛔ |     ✅ |         ⛔ |        ⛔ |
  |          ⛔ |          ⛔ |     ✅ |         ✅ |        ⛔ |
  |          ⛔ |          ✅ |     ⛔ |         ⛔ |        ✅ |
  |          ⛔ |          ✅ |     ⛔ |         ✅ |        ⚠ |
  |          ⛔ |          ✅ |     ✅ |         ⛔ |        ⛔ |
  |          ⛔ |          ✅ |     ✅ |         ✅ |        ⛔ |
  |          ✅ |          ⛔ |     ⛔ |         ⛔ |        ✅ |
  |          ✅ |          ⛔ |     ⛔ |         ✅ |        ✅ |
  |          ✅ |          ⛔ |     ✅ |         ⛔ |        ✅ |
  |          ✅ |          ⛔ |     ✅ |         ✅ |        ✅ |
  |          ✅ |          ✅ |     ⛔ |         ⛔ |        ✅ |
  |          ✅ |          ✅ |     ⛔ |         ✅ |        ✅ |
  |          ✅ |          ✅ |     ✅ |         ⛔ |        ✅ |
  |          ✅ |          ✅ |     ✅ |         ✅ |        ✅ |

  (`⚠` = runs in sandboxed mode)

  ### Known Permissions Paths

  See [[valid-object-path-regex]] for an always-up-to-date list of permissions paths.

    /collection/:id/                                ; read-write perms for a Coll and its non-Coll children
    /collection/:id/read/                           ; read-only  perms for a Coll and its non-Coll children
    /collection/root/                               ; read-write perms for the Root Coll and its non-Coll children
    /colllection/root/read/                         ; read-only  perms for the Root Coll and its non-Coll children
    /collection/namespace/:namespace/root/          ; read-write perms for the Root Coll of a non-default namespace (e.g. SQL Snippets)
    /collection/namespace/:namespace/root/read/     ; read-only  perms for the Root Coll of a non-default namespace (e.g. SQL Snippets)
    /db/:id/                                        ; full perms for a Database
    /db/:id/native/                                 ; ad-hoc native query perms for a Database
    /db/:id/schema/                                 ; ad-hoc MBQL query perms for all schemas in DB (does not include native queries)
    /db/:id/schema/:name/                           ; ad-hoc MBQL query perms for a specific schema
    /db/:id/schema/:name/table/:id/                 ; full perms for a Table
    /db/:id/schema/:name/table/:id/read/            ; perms to fetch info about this Table from the DB
    /db/:id/schema/:name/table/:id/query/           ; ad-hoc MBQL query perms for a Table
    /db/:id/schema/:name/table/:id/query/segmented/ ; [GRANT ONLY] allow ad-hoc MBQL queries. Sandbox all queries against this Table.
    /block/db/:id/                                  ; [GRANT ONLY] disallow queries against this DB unless User has data perms.
    /                                               ; [GRANT ONLY] full root perms"
  (:require [clojure.core.match :refer [match]]
            [clojure.data :as data]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [medley.core :as m]
            [metabase.api.common :refer [*current-user-id*]]
            [metabase.config :as config]
            [metabase.models.interface :as i]
            [metabase.models.permissions-group :as group]
            [metabase.models.permissions-revision :as perms-revision :refer [PermissionsRevision]]
            [metabase.models.permissions.delete-sandboxes :as delete-sandboxes]
            [metabase.models.permissions.parse :as perms-parse]
            [metabase.plugins.classloader :as classloader]
            [metabase.util :as u]
            [metabase.util.honeysql-extensions :as hx]
            [metabase.util.i18n :as ui18n :refer [deferred-tru trs tru]]
            [metabase.util.regex :as u.regex]
            [metabase.util.schema :as su]
            [schema.core :as s]
            [toucan.db :as db]
            [toucan.models :as models]))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                    UTIL FNS                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; -------------------------------------------------- Dynamic Vars --------------------------------------------------

(def ^:dynamic ^Boolean *allow-root-entries*
  "Show we allow permissions entries like `/`? By default, this is disallowed, but you can temporarily disable it here
   when creating the default entry for `Admin`."
  false)

(def ^:dynamic ^Boolean *allow-admin-permissions-changes*
  "Show we allow changes to be made to permissions belonging to the Admin group? By default this is disabled to
   prevent accidental tragedy, but you can enable it here when creating the default entry for `Admin`."
  false)


;;; --------------------------------------------------- Validation ---------------------------------------------------

(def ^:private path-char
  "Valid character for a name that appears in a permissions path (e.g. a schema name or a Collection name). Character is
  valid if it is either:

  1. Any character other than a slash
  2. A forward slash, escaped by a backslash: `\\/`
  3. A backslash escaped by a backslash: `\\\\`"
  (u.regex/rx (or #"[^\\/]" #"\\/" #"\\\\")))

(def ^:private valid-object-path-regex
  "Regex for a valid [[ObjectPath]]. permissions path for an _object_, aka an actual thing that a User can or can't see, such a
  Database, Table, or Collection. The permissions below are permissions that an object can require be present to
  perform a particular action. This is not the same as a [[UserPath]], which is a permissions path that can be
  assigned given to a particular PermissionsGroup. For example, a PermissionsGroup might have root permissions (`/`);
  root permissions are a valid [[UserPath]], but not a valid [[ObjectPath]] since they don't refer to any actual
  object.

  This big-and-hairy regex is built with the [[metabase.util.regex/rx]] util fn to make it somewhat readable."
  (u.regex/rx "^/"
              ;; any path starting with /db/ is a DATA PERMISSIONS path
              (or
               ;; /db/:id/ -> permissions for the entire DB -- native and all schemas
               (and #"db/\d+/"
                    (opt (or
                          ;; .../native/ -> permissions to create new native queries for the DB
                          "native/"
                          ;; .../schema/ -> permissions for all schemas in the DB
                          (and "schema/"
                               ;; .../schema/:name/ -> permissions for a specific schema
                               (opt (and path-char "*/"
                                         ;; .../schema/:name/table/:id/ -> FULL permissions for a specific table
                                         (opt (and #"table/\d+/"
                                                   (opt (or
                                                         ;; .../read/ -> Perms to fetch the Metadata for Table
                                                         "read/"
                                                         ;; .../query/ -> Perms to run any sort of query against Table
                                                         "query/"))))))))))
               ;; any path starting with /collection/ is a COLLECTION permissions path
               (and "collection/"
                    (or
                     ;; /collection/:id/ -> readwrite perms for a specific Collection
                     (and #"\d+/"
                          ;; /collection/:id/read/ -> read perms for a specific Collection
                          (opt "read/"))
                     ;; /collection/root/ -> readwrite perms for the Root Collection
                     (and "root/"
                          ;; /collection/root/read/ -> read perms for the Root Collection
                          (opt "read/"))
                     ;; /collection/namespace/:namespace/root/ -> readwrite perms for 'Root' Collection in non-default
                     ;; namespace (only really used for EE)
                     (and "namespace/" path-char "+/root/"
                          ;; /collection/namespace/:namespace/root/read/ -> read perms for 'Root' Collection in
                          ;; non-default namespace
                          (opt "read/")))))
              "$"))

(def segmented-perm-regex
  "Regex that matches a segmented permission. Used internally for some EE stuff
  e.g. [[metabase-enterprise.sandbox.api.util/segmented-user?]]."
  (re-pattern (str #"^/db/\d+/schema/" path-char "*" #"/table/\d+/query/segmented/$")))

(def ^:private valid-user-path-regex
  "Regex for a valid [[UserPath]]."
  (u.regex/rx
   (or valid-object-path-regex
       ;; segmented (sandboxing) query permissions
       segmented-perm-regex
       ;; any path starting with /block/ is for BLOCK anti-permissions.
       ;; currently only supported at the DB level, e.g. /block/db/1/ => block collection-based access to Database 1
       #"/block/db/\d+/"
       ;; root permissions, i.e. for admin
       #"^/$")))

(defn- escape-path-component
  "Escape slashes in something that might be passed as a string part of a permissions path (e.g. DB schema name or
  Collection name).

    (escape-path-component \"a/b\") ;-> \"a\\/b\""
  [s]
  (some-> s
          (str/replace #"\\" "\\\\\\\\")   ; \ -> \\
          (str/replace #"/" "\\\\/"))) ; / -> \/

(defn valid-object-path?
  "Does `object-path` follow a known, allowed format to an *object*? (The root path, \"/\", is not considered an object;
  this returns `false` for it)."
  ^Boolean [^String object-path]
  (boolean (when (and (string? object-path)
                      (seq object-path))
             (re-matches valid-object-path-regex object-path))))

(def ObjectPath
  "Schema for a valid permissions path to an object."
  (s/pred valid-object-path? "Valid permissions object path."))

(def UserPath
  "Schema for a valid permissions path that a user might possess in their
  [[metabase.api.common/*current-user-permissions-set*]]. This is the same as what's allowed for [[ObjectPath]] but
  also includes root permissions, which admins will have."
  (s/pred #(or (= % "/") (valid-object-path? %))
          "Valid user permissions path."))

(defn- assert-not-admin-group
  "Check to make sure the `:group_id` for `permissions` entry isn't the admin group."
  [{:keys [group_id]}]
  (when (and (= group_id (:id (group/admin)))
             (not *allow-admin-permissions-changes*))
    (throw (ex-info (tru "You cannot create or revoke permissions for the ''Admin'' group.")
             {:status-code 400}))))

(defn- assert-valid-object
  "Check to make sure the value of `:object` for `permissions` entry is valid."
  [{:keys [object]}]
  (when (and object
             (not (valid-object-path? object))
             (or (not= object "/")
                 (not *allow-root-entries*)))
    (throw (ex-info (tru "Invalid permissions object path: ''{0}''." object)
             {:status-code 400, :path object}))))

(defn- assert-valid-metabot-permissions
  "MetaBot permissions can only be created for Collections, since MetaBot can only interact with objects that are always
  in Collections (such as Cards)."
  [{:keys [object group_id]}]
  (when (and (= group_id (:id (group/metabot)))
             (not (str/starts-with? object "/collection/")))
    (throw (ex-info (tru "MetaBot can only have Collection permissions.")
             {:status-code 400}))))

(defn- assert-valid
  "Check to make sure this `permissions` entry is something that's allowed to be saved (i.e. it has a valid `:object`
   path and it's not for the admin group)."
  [permissions]
  (doseq [f [assert-not-admin-group
             assert-valid-object
             assert-valid-metabot-permissions]]
    (f permissions)))


;;; ------------------------------------------------- Path Util Fns --------------------------------------------------

(def ^:private MapOrID
  (s/cond-pre su/Map su/IntGreaterThanZero))

(s/defn object-path :- ObjectPath
  "Return the [readwrite] permissions path for a Database, schema, or Table. (At the time of this writing, DBs and
  schemas don't have separate `read/` and write permissions; you either have 'data access' permissions for them, or
  you don't. Tables, however, have separate read and write perms.)"
  ([database-or-id :- MapOrID]
   (str "/db/" (u/the-id database-or-id) "/"))

  ([database-or-id :- MapOrID, schema-name :- (s/maybe s/Str)]
   (str (object-path database-or-id) "schema/" (escape-path-component schema-name) "/"))

  ([database-or-id :- MapOrID, schema-name :- (s/maybe s/Str), table-or-id :- MapOrID]
   (str (object-path database-or-id schema-name) "table/" (u/the-id table-or-id) "/" )))

(s/defn adhoc-native-query-path :- ObjectPath
  "Return the native query read/write permissions path for a database.
   This grants you permissions to run arbitary native queries."
  [database-or-id :- MapOrID]
  (str (object-path database-or-id) "native/"))

(s/defn all-schemas-path :- ObjectPath
  "Return the permissions path for a database that grants full access to all schemas."
  [database-or-id :- MapOrID]
  (str (object-path database-or-id) "schema/"))

(s/defn collection-readwrite-path :- ObjectPath
  "Return the permissions path for *readwrite* access for a `collection-or-id`."
  [collection-or-id :- MapOrID]
  (if-not (get collection-or-id :metabase.models.collection.root/is-root?)
    (format "/collection/%d/" (u/the-id collection-or-id))
    (if-let [collection-namespace (:namespace collection-or-id)]
      (format "/collection/namespace/%s/root/" (escape-path-component (u/qualified-name collection-namespace)))
      "/collection/root/")))

(s/defn collection-read-path :- ObjectPath
  "Return the permissions path for *read* access for a `collection-or-id`."
  [collection-or-id :- MapOrID]
  (str (collection-readwrite-path collection-or-id) "read/"))

(s/defn table-read-path :- ObjectPath
  "Return the permissions path required to fetch the Metadata for a Table."
  ([table]
   (table-read-path (:db_id table) (:schema table) table))

  ([database-or-id schema-name table-or-id]
   {:post [(valid-object-path? %)]}
   (str (object-path (u/the-id database-or-id) schema-name (u/the-id table-or-id)) "read/")))

(s/defn table-query-path :- ObjectPath
  "Return the permissions path for *full* query access for a Table. Full query access means you can run any (MBQL) query
  you wish against a given Table, with no GTAP-specified mandatory query alterations."
  ([table]
   (table-query-path (:db_id table) (:schema table) table))

  ([database-or-id schema-name table-or-id]
   (str (object-path (u/the-id database-or-id) schema-name (u/the-id table-or-id)) "query/")))

(s/defn table-segmented-query-path :- ObjectPath
  "Return the permissions path for *segmented* query access for a Table. Segmented access means running queries against
  the Table will automatically replace the Table with a GTAP-specified question as the new source of the query,
  obstensibly limiting access to the results."
  ([table]
   (table-segmented-query-path (:db_id table) (:schema table) table))

  ([database-or-id schema-name table-or-id]
   (str (object-path (u/the-id database-or-id) schema-name (u/the-id table-or-id)) "query/segmented/")))


;;; -------------------------------------------- Permissions Checking Fns --------------------------------------------

(defn is-permissions-for-object?
  "Does `permissions-path` grant *full* access for `object-path`?"
  [permissions-path object-path]
  (str/starts-with? object-path permissions-path))

(defn is-partial-permissions-for-object?
  "Does `permissions-path` grant access full access for `object-path` *or* for a descendant of `object-path`?"
  [permissions-path object-path]
  (or (is-permissions-for-object? permissions-path object-path)
      (str/starts-with? permissions-path object-path)))

(defn is-permissions-set?
  "Is `permissions-set` a valid set of permissions object paths?"
  ^Boolean [permissions-set]
  (and (set? permissions-set)
       (every? (fn [path]
                 (or (= path "/")
                     (valid-object-path? path)))
               permissions-set)))

(defn set-has-full-permissions?
  "Does `permissions-set` grant *full* access to object with `path`?"
  ^Boolean [permissions-set path]
  (boolean (some #(is-permissions-for-object? % path) permissions-set)))

(defn set-has-partial-permissions?
  "Does `permissions-set` grant access full access to object with `path` *or* to a descendant of it?"
  ^Boolean [permissions-set path]
  (boolean (some #(is-partial-permissions-for-object? % path) permissions-set)))

(s/defn set-has-full-permissions-for-set? :- s/Bool
  "Do the permissions paths in `permissions-set` grant *full* access to all the object paths in `object-paths-set`?"
  [permissions-set :- #{UserPath} object-paths-set :- #{ObjectPath}]
  (every? (partial set-has-full-permissions? permissions-set)
          object-paths-set))

(s/defn set-has-partial-permissions-for-set? :- s/Bool
  "Do the permissions paths in `permissions-set` grant *partial* access to all the object paths in `object-paths-set`?
   (`permissions-set` must grant partial access to *every* object in `object-paths-set` set)."
  [permissions-set :- #{UserPath}, object-paths-set :- #{ObjectPath}]
  (every? (partial set-has-partial-permissions? permissions-set)
          object-paths-set))

(s/defn perms-objects-set-for-parent-collection :- #{ObjectPath}
  "Implementation of `IModel` `perms-objects-set` for models with a `collection_id`, such as Card, Dashboard, or Pulse.
  This simply returns the `perms-objects-set` of the parent Collection (based on `collection_id`), or for the Root
  Collection if `collection_id` is `nil`."
  ([this read-or-write]
   (perms-objects-set-for-parent-collection nil this read-or-write))

  ([collection-namespace :- (s/maybe su/KeywordOrString)
    this                 :- {:collection_id (s/maybe su/IntGreaterThanZero), s/Keyword s/Any}
    read-or-write        :- (s/enum :read :write)]
   ;; based on value of read-or-write determine the approprite function used to calculate the perms path
   (let [path-fn (case read-or-write
                   :read  collection-read-path
                   :write collection-readwrite-path)]
     ;; now pass that function our collection_id if we have one, or if not, pass it an object representing the Root
     ;; Collection
     #{(path-fn (or (:collection_id this)
                    {:metabase.models.collection.root/is-root? true
                     :namespace                                collection-namespace}))})))

(def IObjectPermissionsForParentCollection
  "Implementation of `IObjectPermissions` for objects that have a `collection_id`, and thus, a parent Collection.
   Using this will mean the current User is allowed to read or write these objects if they are allowed to read or
  write their parent Collection."
  (merge i/IObjectPermissionsDefaults
         ;; TODO - we use these same partial implementations of `can-read?` and `can-write?` all over the place for
         ;; different models. Consider making them a mixin of some sort. (I was going to do this but I couldn't come
         ;; up with a good name for the Mixin. - Cam)
         {:can-read?         (partial i/current-user-has-full-permissions? :read)
          :can-write?        (partial i/current-user-has-full-permissions? :write)
          :perms-objects-set perms-objects-set-for-parent-collection}))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                               ENTITY + LIFECYCLE                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(models/defmodel Permissions :permissions)

(defn- pre-insert [permissions]
  (u/prog1 permissions
    (assert-valid permissions)
    (log/debug (u/colorize 'green (trs "Granting permissions for group {0}: {1}" (:group_id permissions) (:object permissions))))))

(defn- pre-update [_]
  (throw (Exception. (str (deferred-tru "You cannot update a permissions entry!")
                          (deferred-tru "Delete it and create a new one.")))))

(defn- pre-delete [permissions]
  (log/debug (u/colorize 'red (trs "Revoking permissions for group {0}: {1}" (:group_id permissions) (:object permissions))))
  (assert-not-admin-group permissions))

(u/strict-extend (class Permissions)
  models/IModel (merge models/IModelDefaults
                   {:pre-insert         pre-insert
                    :pre-update         pre-update
                    :pre-delete pre-delete}))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  GRAPH SCHEMA                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

;; TODO - there is so much stuff related to the perms graph I think we should really move it into a separate
;; `metabase.models.permissions.graph.data` namespace or something and move the collections graph from
;; `metabase.models.collection` to `metabase.models.permissions.graph.collection` (?)

(def ^:private TablePermissionsGraph
  (s/named
    (s/cond-pre (s/enum :none :all)
                (s/constrained
                  {(s/optional-key :read)  (s/enum :all :none)
                   (s/optional-key :query) (s/enum :all :segmented :none)}
                  not-empty))
    "Valid perms graph for a Table"))

(def ^:private SchemaPermissionsGraph
  (s/named
   (s/cond-pre (s/enum :none :all)
               {su/IntGreaterThanZero TablePermissionsGraph})
   "Valid perms graph for a schema"))

(def ^:private NativePermissionsGraph
  (s/named
   (s/enum :write :none)
   "Valid native perms option for a database"))

(def ^:private DBPermissionsGraph
  (s/named
   {(s/optional-key :native)  NativePermissionsGraph
    (s/optional-key :schemas) (s/cond-pre (s/enum :all :none)
                                          {s/Str SchemaPermissionsGraph})}
   "Valid perms graph for a Database"))

(def ^:private GroupPermissionsGraph
  (s/named
   {su/IntGreaterThanZero DBPermissionsGraph}
   "Valid perms graph for a PermissionsGroup"))

(def ^:private PermissionsGraph
  (s/named
   {:revision s/Int
    :groups   {su/IntGreaterThanZero GroupPermissionsGraph}}
   "Valid perms graph"))

;; The "Strict" versions of the various graphs below are intended for schema checking when *updating* the permissions
;; graph. In other words, we shouldn't be stopped from returning the graph if it violates the "strict" rules, but we
;; *should* refuse to update the graph unless it matches the strict schema.
;;
;; TODO - It might be possible at some point in the future to just use the strict versions everywhere

(defn- check-native-and-schemas-permissions-allowed-together [{:keys [native schemas]}]
  ;; Only do the check when we have both, e.g. when the entire graph is coming in
  (if-not (and native schemas)
    :ok
    (match [native schemas]
      [:write :all]  :ok
      [:write _]     (log/warn "Invalid DB permissions: if you have write access for native queries, you must have full data access.")
      [:read  :none] (log/warn "Invalid DB permissions: if you have readonly native query access, you must also have data access.")
      [_      _]     :ok)))

(def ^:private StrictDBPermissionsGraph
  (s/constrained DBPermissionsGraph
                 check-native-and-schemas-permissions-allowed-together
                 "DB permissions with a valid combination of values for :native and :schemas"))

(def ^:private StrictGroupPermissionsGraph
  {su/IntGreaterThanZero StrictDBPermissionsGraph})

(def ^:private StrictPermissionsGraph
  {:revision s/Int
   :groups   {su/IntGreaterThanZero StrictGroupPermissionsGraph}})


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  GRAPH FETCH                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn all-permissions
  "Handle '/' permission"
  [db-ids]
  (reduce (fn [g db-id]
            (assoc g db-id {:native  :write
                            :schemas :all}))
          {}
          db-ids))

(s/defn graph :- PermissionsGraph
  "Fetch a graph representing the current permissions status for every Group and all permissioned databases."
  []
  (let [permissions (db/select [Permissions :group_id :object], :group_id [:not= (:id (group/metabot))])
        db-ids      (db/select-ids 'Database)]
    {:revision (perms-revision/latest-id)
     :groups   (->> permissions
                    (filter (comp #(re-find #"(^/db|^/$)" %) :object))
                    (group-by :group_id)
                    (m/map-vals (fn [group-permissions]
                                  (let [permissions-graph (perms-parse/permissions->graph (map :object group-permissions))]
                                    (if (= :all permissions-graph)
                                      (all-permissions db-ids)
                                      (:db permissions-graph))))))}))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  GRAPH UPDATE                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; --------------------------------------------------- Helper Fns ---------------------------------------------------

(s/defn ^:private delete-related-permissions!
  "This is somewhat hard to explain, but I will do my best:

  Delete all 'related' permissions for `group-or-id` (i.e., perms that grant you full or partial access to `path`).
  This includes *both* ancestor and descendant paths. For example:

  Suppose we asked this functions to delete related permssions for `/db/1/schema/PUBLIC/`. Dependning on the
  permissions the group has, it could end up doing something like:

    *  deleting `/db/1/` permissions (because the ancestor perms implicity grant you full perms for `schema/PUBLIC`)
    *  deleting perms for `/db/1/schema/PUBLIC/table/2/` (because Table 2 is a descendant of `schema/PUBLIC`)

  In short, it will delete any permissions that contain `/db/1/schema/` as a prefix, or that themeselves are prefixes
  for `/db/1/schema/`.

  You can optionally include `other-conditions`, which are anded into the filter clause, to further restrict what is
  deleted.

  NOTE: This function is meant for internal usage in this namespace only; use one of the other functions like
  `revoke-permissions!` elsewhere instead of calling this directly."
  {:style/indent 2}
  [group-or-id :- (s/cond-pre su/Map su/IntGreaterThanZero), path :- ObjectPath, & other-conditions]
  (let [where {:where (apply list
                             :and
                             [:= :group_id (u/the-id group-or-id)]
                             [:or
                              [:like path (hx/concat :object (hx/literal "%"))]
                              [:like :object (str path "%")]]
                             other-conditions)}]
    (when-let [revoked (db/select-field :object Permissions where)]
      (log/debug (u/format-color 'red "Revoking permissions for group %d: %s" (u/the-id group-or-id) revoked))
      (db/delete! Permissions where))))

(defn revoke-permissions!
  "Revoke all permissions for `group-or-id` to object with `path-components`, *including* related permissions (i.e,
  permissions that grant full or partial access to the object in question).


    (revoke-permissions! my-group my-db)"
  {:arglists '([group-id database-or-id]
               [group-id database-or-id schema-name]
               [group-id database-or-id schema-name table-or-id])}
  [group-or-id & path-components]
  (delete-related-permissions! group-or-id (apply object-path path-components)))

(defn grant-permissions!
  "Grant permissions to `group-or-id` to an object."
  ([group-or-id db-id schema & more]
   (grant-permissions! group-or-id (apply object-path db-id schema more)))
  ([group-or-id path]
   (try
     (db/insert! Permissions
       :group_id (u/the-id group-or-id)
       :object   path)
     ;; on some occasions through weirdness we might accidentally try to insert a key that's already been inserted
     (catch Throwable e
       (log/error e (u/format-color 'red (tru "Failed to grant permissions")))
       ;; if we're running tests, we're doing something wrong here if duplicate permissions are getting assigned,
       ;; mostly likely because tests aren't properly cleaning up after themselves, and possibly causing other tests
       ;; to pass when they shouldn't. Don't allow this during tests
       (when config/is-test?
         (throw e))))))

(defn revoke-native-permissions!
  "Revoke all native query permissions for `group-or-id` to database with `database-id`."
  [group-or-id database-or-id]
  (delete-related-permissions! group-or-id (adhoc-native-query-path database-or-id)))

(defn grant-native-readwrite-permissions!
  "Grant full readwrite permissions for `group-or-id` to database with `database-id`."
  [group-or-id database-or-id]
  (grant-permissions! group-or-id (adhoc-native-query-path database-or-id)))

(defn revoke-db-schema-permissions!
  "Remove all permissions entires for a DB and *any* child objects.
   This does *not* revoke native permissions; use `revoke-native-permssions!` to do that."
  [group-or-id database-or-id]
  ;; TODO - if permissions for this DB are DB root entries like `/db/1/` won't this end up removing our native perms?
  (delete-related-permissions! group-or-id (object-path database-or-id)
    [:not= :object (adhoc-native-query-path database-or-id)]))

(defn grant-permissions-for-all-schemas!
  "Grant full permissions for all schemas belonging to this database.
   This does *not* grant native permissions; use `grant-native-readwrite-permissions!` to do that."
  [group-or-id database-or-id]
  (grant-permissions! group-or-id (all-schemas-path database-or-id)))

(defn grant-full-db-permissions!
  "Grant full access to the database, including all schemas and readwrite native access."
  [group-or-id database-or-id]
  (grant-permissions! group-or-id (object-path database-or-id)))

(defn- is-personal-collection-or-descendant-of-one? [collection]
  (classloader/require 'metabase.models.collection)
  ((resolve 'metabase.models.collection/is-personal-collection-or-descendant-of-one?) collection))

(s/defn ^:private check-not-personal-collection-or-descendant
  "Check whether `collection-or-id` refers to a Personal Collection; if so, throw an Exception. This is done because we
  *should* never be editing granting/etc. permissions for *Personal* Collections to entire Groups! Their owner will
  get implicit permissions automatically, and of course admins will be able to see them,but a whole group should never
  be given some sort of access."
  [collection-or-id :- MapOrID]
  ;; don't apply this check to the Root Collection, because it's never personal
  (when-not (:metabase.models.collection.root/is-root? collection-or-id)
    ;; ok, once we've confirmed this isn't the Root Collection, see if it's in the DB with a personal_owner_id
    (let [collection (if (map? collection-or-id)
                       collection-or-id
                       (or (db/select-one 'Collection :id (u/the-id collection-or-id))
                           (throw (ex-info (tru "Collection does not exist.") {:collection-id (u/the-id collection-or-id)}))))]
      (when (is-personal-collection-or-descendant-of-one? collection)
        (throw (Exception. (tru "You cannot edit permissions for a Personal Collection or its descendants.")))))))

(s/defn revoke-collection-permissions!
  "Revoke all access for `group-or-id` to a Collection."
  [group-or-id :- MapOrID collection-or-id :- MapOrID]
  (check-not-personal-collection-or-descendant collection-or-id)
  (delete-related-permissions! group-or-id (collection-readwrite-path collection-or-id)))

(s/defn grant-collection-readwrite-permissions!
  "Grant full access to a Collection, which means a user can view all Cards in the Collection and add/remove Cards."
  [group-or-id :- MapOrID collection-or-id :- MapOrID]
  (check-not-personal-collection-or-descendant collection-or-id)
  (grant-permissions! (u/the-id group-or-id) (collection-readwrite-path collection-or-id)))

(s/defn grant-collection-read-permissions!
  "Grant read access to a Collection, which means a user can view all Cards in the Collection."
  [group-or-id :- MapOrID collection-or-id :- MapOrID]
  (check-not-personal-collection-or-descendant collection-or-id)
  (grant-permissions! (u/the-id group-or-id) (collection-read-path collection-or-id)))


;;; ----------------------------------------------- Graph Updating Fns -----------------------------------------------

(s/defn ^:private update-table-read-perms!
  [group-id       :- su/IntGreaterThanZero
   db-id          :- su/IntGreaterThanZero
   schema         :- s/Str
   table-id       :- su/IntGreaterThanZero
   new-read-perms :- (s/enum :all :none)]
  ((case new-read-perms
     :all  grant-permissions!
     :none revoke-permissions!) group-id (table-read-path db-id schema table-id)))

(s/defn ^:private update-table-query-perms!
  [group-id        :- su/IntGreaterThanZero
   db-id           :- su/IntGreaterThanZero
   schema          :- s/Str
   table-id        :- su/IntGreaterThanZero
   new-query-perms :- (s/enum :all :segmented :none)]
  (case new-query-perms
    :all       (grant-permissions!  group-id (table-query-path           db-id schema table-id))
    :segmented (grant-permissions!  group-id (table-segmented-query-path db-id schema table-id))
    :none      (revoke-permissions! group-id (table-query-path           db-id schema table-id))))

(s/defn ^:private update-table-perms!
  [group-id        :- su/IntGreaterThanZero
   db-id           :- su/IntGreaterThanZero
   schema          :- s/Str
   table-id        :- su/IntGreaterThanZero
   new-table-perms :- TablePermissionsGraph]
  (cond
    (= new-table-perms :all)
    (grant-permissions! group-id db-id schema table-id)

    (= new-table-perms :none)
    (revoke-permissions! group-id db-id schema table-id)

    (map? new-table-perms)
    (let [{new-read-perms :read, new-query-perms :query} new-table-perms]
      ;; clear out any existing permissions
      (revoke-permissions! group-id db-id schema table-id)
      ;; then grant/revoke read and query perms as appropriate
      (when new-read-perms  (update-table-read-perms!  group-id db-id schema table-id new-read-perms))
      (when new-query-perms (update-table-query-perms! group-id db-id schema table-id new-query-perms)))))

(s/defn ^:private update-schema-perms!
  [group-id         :- su/IntGreaterThanZero
   db-id            :- su/IntGreaterThanZero
   schema           :- s/Str
   new-schema-perms :- SchemaPermissionsGraph]
  (cond
    (= new-schema-perms :all)  (do (revoke-permissions! group-id db-id schema)  ; clear out any existing related permissions
                                   (grant-permissions!  group-id db-id schema)) ; then grant full perms for the schema
    (= new-schema-perms :none) (revoke-permissions! group-id db-id schema)
    (map? new-schema-perms)    (doseq [[table-id table-perms] new-schema-perms]
                                 (update-table-perms! group-id db-id schema table-id table-perms))))

(s/defn ^:private update-native-permissions!
  [group-id :- su/IntGreaterThanZero, db-id :- su/IntGreaterThanZero, new-native-perms :- NativePermissionsGraph]
  ;; revoke-native-permissions! will delete all entires that would give permissions for native access. Thus if you had
  ;; a root DB entry like `/db/11/` this will delete that too. In that case we want to create a new full schemas entry
  ;; so you don't lose access to all schemas when we modify native access.
  (let [has-full-access? (db/exists? Permissions :group_id group-id, :object (object-path db-id))]
    (revoke-native-permissions! group-id db-id)
    (when has-full-access?
      (grant-permissions-for-all-schemas! group-id db-id)))
  (case new-native-perms
    :write (grant-native-readwrite-permissions! group-id db-id)
    :none  nil))


(s/defn ^:private update-db-permissions!
  [group-id :- su/IntGreaterThanZero, db-id :- su/IntGreaterThanZero, new-db-perms :- StrictDBPermissionsGraph]
  (when-let [new-native-perms (:native new-db-perms)]
    (update-native-permissions! group-id db-id new-native-perms))
  (when-let [schemas (:schemas new-db-perms)]
    (cond
      (= schemas :all)  (do (revoke-db-schema-permissions! group-id db-id)
                            (grant-permissions-for-all-schemas! group-id db-id))
      (= schemas :none) (revoke-db-schema-permissions! group-id db-id)
      (map? schemas)    (doseq [schema (keys schemas)]
                          (update-schema-perms! group-id db-id schema (get-in new-db-perms [:schemas schema]))))))

(s/defn ^:private update-group-permissions!
  [group-id :- su/IntGreaterThanZero, new-group-perms :- StrictGroupPermissionsGraph]
  (doseq [[db-id new-db-perms] new-group-perms]
    (update-db-permissions! group-id db-id new-db-perms)))


(defn check-revision-numbers
  "Check that the revision number coming in as part of NEW-GRAPH matches the one from OLD-GRAPH.
   This way we can make sure people don't submit a new graph based on something out of date,
   which would otherwise stomp over changes made in the interim.
   Return a 409 (Conflict) if the numbers don't match up."
  [old-graph new-graph]
  (when (not= (:revision old-graph) (:revision new-graph))
    (throw (ex-info (str (deferred-tru "Looks like someone else edited the permissions and your data is out of date.")
                               " "
                               (deferred-tru "Please fetch new data and try again."))
             {:status-code 409}))))

(defn- save-perms-revision!
  "Save changes made to the permissions graph for logging/auditing purposes.
   This doesn't do anything if `*current-user-id*` is unset (e.g. for testing or REPL usage)."
  [current-revision old new]
  (when *current-user-id*
    (db/insert! PermissionsRevision
      ;; manually specify ID here so if one was somehow inserted in the meantime in the fraction of a second since we
      ;; called `check-revision-numbers` the PK constraint will fail and the transaction will abort
      :id     (inc current-revision)
      :before  old
      :after   new
      :user_id *current-user-id*)))

(defn log-permissions-changes
  "Log changes to the permissions graph."
  [old new]
  (log/debug
   (trs "Changing permissions")
   "\n" (trs "FROM:") (u/pprint-to-str 'magenta old)
   "\n" (trs "TO:")   (u/pprint-to-str 'blue    new)))

(s/defn update-graph!
  "Update the permissions graph, making any changes necessary to make it match NEW-GRAPH.
   This should take in a graph that is exactly the same as the one obtained by `graph` with any changes made as
   needed. The graph is revisioned, so if it has been updated by a third party since you fetched it this function will
   fail and return a 409 (Conflict) exception. If nothing needs to be done, this function returns `nil`; otherwise it
   returns the newly created `PermissionsRevision` entry."
  ([new-graph :- StrictPermissionsGraph]
   (let [old-graph (graph)
         [old new] (data/diff (:groups old-graph) (:groups new-graph))
         old       (or old {})]
     (when (or (seq old) (seq new))
       (log-permissions-changes old new)
       (check-revision-numbers old-graph new-graph)
       (db/transaction
         (doseq [[group-id changes] new]
           (update-group-permissions! group-id changes))
         (save-perms-revision! (:revision old-graph) old new)
         (delete-sandboxes/delete-gtaps-if-needed-after-permissions-change! new)))))

  ;; The following arity is provided soley for convenience for tests/REPL usage
  ([ks :- [s/Any], new-value]
   (update-graph! (assoc-in (graph) (cons :groups ks) new-value))))
