import { restore, USER, signInAsNormalUser } from "__support__/cypress";

describe("smoketest > new_user", () => {
    before(restore);
    before(signInAsNormalUser);
  
    it("should be able to do header actions", () => {

        // =================
        // should be able to ask a custom question
        // =================
        cy.visit("/");

        cy.findByText("Ask a question").click();
        cy.contains("Simple question");

        cy.findByText("Custom question").click();
        cy.findByText("Sample Dataset").click();
        cy.findByText("Products").click();
        cy.findByText("Add filters to narrow your answer").click();
        cy.findByText("Vendor").click();
        cy.findByText("Is").click();
        cy.findByText("Not empty").click();
        cy.findByText("Add filter").click();
        cy.findByText("Pick the metric you want to see").click();
        cy.findByText("Average of ...").click();
        cy.findByText("Rating").click();
        cy.findByText("Pick a column to group by").click();
        cy.findByText("Title").click();

        cy.contains("Average of Rating");

        cy.findByText("Visualize").click();
        
        cy.contains("Vendor is not empty");
        cy.contains("Visualization");
        
        // =================
        // should ensuring that header actions are appropriate for different data types
        // =================
        
        cy.contains("Filter").click();
        
        // =================
        // should filter via both the sidebar and the header
        // =================



        // =================
        // should summarize via both the sidebar and the header
        // =================



        // =================
        // should be able to create custom columns in the notebook editor
        // =================

        // =================
        // should be able to create custom JOINs in the notebook editor
        // =================
    });
});
