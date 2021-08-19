import { restore, popover, modal } from "__support__/e2e/cypress";

const COLLECTION_ACCESS_PERMISSION_INDEX = 0;

const DATA_ACCESS_PERMISSION_INDEX = 0;
const NATIVE_QUERIES_PERMISSION_INDEX = 1;

describe("scenarios > admin > permissions", () => {
  beforeEach(() => {
    restore();
    cy.signInAsAdmin();
  });

  it("should display error on failed save", () => {
    // revoke some permissions
    cy.visit("/admin/permissions/data/group/1");
    cy.icon("close")
      .first()
      .click();
    cy.findAllByRole("option")
      .contains("Allowed")
      .click();

    // stub out the PUT and save
    cy.server();
    cy.route({
      method: "PUT",
      url: /\/api\/permissions\/graph$/,
      status: 500,
      response: "Server error",
    });
    cy.contains("Save changes").click();
    cy.contains("button", "Yes").click();

    // see error modal
    cy.contains("Server error");
    cy.contains("There was an error saving");
  });

  context("collection permissions", () => {
    it("warns about leaving with unsaved changes", () => {
      cy.visit("/admin/permissions/collections");

      selectSidebarItem("First collection");

      modifyPermission(
        "All Users",
        COLLECTION_ACCESS_PERMISSION_INDEX,
        "View",
        true,
      );

      // Navigation to other collection should not show any warnings
      selectSidebarItem("Our analytics");

      modal().should("not.exist");

      // Switching to data permissions page
      cy.get("label")
        .contains("Data permissions")
        .click();

      modal().within(() => {
        cy.findByText("Discard your unsaved changes?");
        cy.findByText(
          "If you leave this page now, your changes won't be saved.",
        );

        cy.button("Cancel").click();
      });

      cy.url().should("include", "/admin/permissions/collections/root");

      // Switching to data permissions page again
      cy.get("label")
        .contains("Data permissions")
        .click();

      modal().within(() => {
        cy.button("Discard changes").click();
      });

      cy.url().should("include", "/admin/permissions/data/group");
    });

    it("allows to view and edit permissions", () => {
      cy.visit("/admin/permissions/collections");

      const collections = ["Our analytics", "First collection"];
      assertSidebarItems(collections);

      selectSidebarItem("First collection");
      assertSidebarItems([...collections, "Second collection"]);

      selectSidebarItem("Second collection");

      assertPermissionTable([
        ["Administrators", "Curate"],
        ["All Users", "No access"],
        ["collection", "Curate"],
        ["data", "No access"],
        ["nosql", "No access"],
        ["readonly", "View"],
      ]);

      modifyPermission(
        "All Users",
        COLLECTION_ACCESS_PERMISSION_INDEX,
        "View",
        true,
      );

      // Navigate to children
      selectSidebarItem("Third collection");

      assertPermissionTable([
        ["Administrators", "Curate"],
        ["All Users", "View"], // Check permission has been propagated
        ["collection", "Curate"],
        ["data", "No access"],
        ["nosql", "No access"],
        ["readonly", "View"],
      ]);

      // Navigate to parent
      selectSidebarItem("First collection");

      assertPermissionTable([
        ["Administrators", "Curate"],
        ["All Users", "No access"],
        ["collection", "Curate"],
        ["data", "No access"],
        ["nosql", "No access"],
        ["readonly", "View"],
      ]);

      modifyPermission(
        "All Users",
        COLLECTION_ACCESS_PERMISSION_INDEX,
        "Curate",
        false,
      );

      selectSidebarItem("First collection"); // Expand children
      selectSidebarItem("Second collection");

      assertPermissionTable([
        ["Administrators", "Curate"],
        ["All Users", "View"], // Check permission has not been propagated
        ["collection", "Curate"],
        ["data", "No access"],
        ["nosql", "No access"],
        ["readonly", "View"],
      ]);

      cy.button("Save changes").click();

      modal().within(() => {
        cy.findByText("Save permissions?");
        cy.findByText("Are you sure you want to do this?");
        cy.button("Yes").click();
      });

      cy.findByText("Save changes").should("not.exist");

      assertPermissionTable([
        ["Administrators", "Curate"],
        ["All Users", "View"],
        ["collection", "Curate"],
        ["data", "No access"],
        ["nosql", "No access"],
        ["readonly", "View"],
      ]);
    });
  });

  context("data permissions", () => {
    it("warns about leaving with unsaved changes", () => {
      cy.visit("/admin/permissions");

      selectSidebarItem("All Users");

      modifyPermission(
        "Sample Dataset",
        DATA_ACCESS_PERMISSION_INDEX,
        "Allowed",
      );

      cy.findByText("You've made changes to permissions.");

      // Switching to databases focus should not show any warnings
      cy.get("label")
        .contains("Databases")
        .click();

      cy.url().should("include", "/admin/permissions/data/database");
      modal().should("not.exist");

      // Switching to collection permissions page
      cy.get("label")
        .contains("Collection permissions")
        .click();

      modal().within(() => {
        cy.findByText("Discard your unsaved changes?");
        cy.findByText(
          "If you leave this page now, your changes won't be saved.",
        );

        cy.button("Cancel").click();
      });

      cy.url().should("include", "/admin/permissions/data/database");

      // Switching to collection permissions page again
      cy.get("label")
        .contains("Collection permissions")
        .click();

      modal().within(() => {
        cy.button("Discard changes").click();
      });

      cy.url().should("include", "/admin/permissions/collections");
    });

    context("group focused view", () => {
      it("shows filterable list of groups", () => {
        cy.visit("/admin/permissions");

        // no groups selected initially and it shows an empty state
        cy.findByText("Select a group to see it's data permissions");

        const groups = [
          "Administrators",
          "All Users",
          "collection",
          "data",
          "nosql",
          "readonly",
        ];

        assertSidebarItems(groups);

        // filter groups
        cy.findByPlaceholderText("Search for a group").type("a");

        const filteredGroups = [
          "Administrators",
          "All Users",
          "data",
          "readonly",
        ];

        // client filter devounce
        cy.wait(300);

        assertSidebarItems(filteredGroups);
      });

      it("allows to only view Administrators permissions", () => {
        cy.visit("/admin/permissions");

        selectSidebarItem("Administrators");

        cy.url().should("include", "/admin/permissions/data/group/2");

        cy.findByText("Permissions for the Administrators group");
        cy.findByText("1 person");

        checkAdministratorsHaveAccessToEverything();

        // Drill down to tables permissions
        cy.findByText("Sample Dataset").click();

        checkAdministratorsHaveAccessToEverything();
      });

      it("allows view and edit permissions", () => {
        cy.visit("/admin/permissions");

        selectSidebarItem("collection");

        assertPermissionTable([["Sample Dataset", "No access", "No access"]]);

        // Drill down to tables permissions
        cy.findByText("Sample Dataset").click();

        assertPermissionTable([
          ["Orders", "No access"],
          ["People", "No access"],
          ["Products", "No access"],
          ["Reviews", "No access"],
        ]);

        modifyPermission("Orders", DATA_ACCESS_PERMISSION_INDEX, "Allowed");

        modal().within(() => {
          cy.findByText("Change access to this database to limited?");
          cy.button("Change").click();
        });

        assertPermissionTable([
          ["Orders", "Allowed"],
          ["People", "No access"],
          ["Products", "No access"],
          ["Reviews", "No access"],
        ]);

        // Navigate back
        selectSidebarItem("collection");

        assertPermissionTable([["Sample Dataset", "Limited", "No access"]]);

        modifyPermission(
          "Sample Dataset",
          NATIVE_QUERIES_PERMISSION_INDEX,
          "Allowed",
        );

        modal().within(() => {
          cy.findByText("Allow native query editing?");
          cy.button("Allow").click();
        });

        assertPermissionTable([["Sample Dataset", "Allowed", "Allowed"]]);

        // Drill down to tables permissions
        cy.findByText("Sample Dataset").click();

        assertPermissionTable([
          ["Orders", "Allowed"],
          ["People", "Allowed"],
          ["Products", "Allowed"],
          ["Reviews", "Allowed"],
        ]);

        cy.button("Save changes").click();

        modal().within(() => {
          cy.findByText("Save permissions?");
          cy.contains(
            "collection will be given access to 4 tables in Sample Dataset.",
          );
          cy.contains(
            "collection will now be able to write native queries for Sample Dataset.",
          );
          cy.button("Yes").click();
        });

        cy.findByText("Save changes").should("not.exist");

        assertPermissionTable([
          ["Orders", "Allowed"],
          ["People", "Allowed"],
          ["Products", "Allowed"],
          ["Reviews", "Allowed"],
        ]);
      });
    });

    context("database focused view", () => {
      it("allows view and edit permissions", () => {
        cy.visit("/admin/permissions/");

        cy.get("label")
          .contains("Databases")
          .click();

        cy.findByText("Select a database to see group permissions");

        selectSidebarItem("Sample Dataset");

        assertPermissionTable([
          ["Administrators", "Allowed", "Allowed"],
          ["All Users", "No access", "No access"],
          ["collection", "No access", "No access"],
          ["data", "Allowed", "Allowed"],
          ["nosql", "Allowed", "No access"],
          ["readonly", "No access", "No access"],
        ]);

        selectSidebarItem("Orders");

        assertPermissionTable([
          ["Administrators", "Allowed"],
          ["All Users", "No access"],
          ["collection", "No access"],
          ["data", "Allowed"],
          ["nosql", "Allowed"],
          ["readonly", "No access"],
        ]);

        modifyPermission("readonly", DATA_ACCESS_PERMISSION_INDEX, "Allowed");

        modal().within(() => {
          cy.findByText("Change access to this database to limited?");
          cy.button("Change").click();
        });

        assertPermissionTable([
          ["Administrators", "Allowed"],
          ["All Users", "No access"],
          ["collection", "No access"],
          ["data", "Allowed"],
          ["nosql", "Allowed"],
          ["readonly", "Allowed"],
        ]);

        // Navigate back
        cy.get("a")
          .contains("Sample Dataset")
          .click();

        assertPermissionTable([
          ["Administrators", "Allowed", "Allowed"],
          ["All Users", "No access", "No access"],
          ["collection", "No access", "No access"],
          ["data", "Allowed", "Allowed"],
          ["nosql", "Allowed", "No access"],
          ["readonly", "Limited", "No access"],
        ]);

        modifyPermission(
          "readonly",
          NATIVE_QUERIES_PERMISSION_INDEX,
          "Allowed",
        );

        modal().within(() => {
          cy.findByText("Allow native query editing?");
          cy.button("Allow").click();
        });

        assertPermissionTable([
          ["Administrators", "Allowed", "Allowed"],
          ["All Users", "No access", "No access"],
          ["collection", "No access", "No access"],
          ["data", "Allowed", "Allowed"],
          ["nosql", "Allowed", "No access"],
          ["readonly", "Allowed", "Allowed"],
        ]);

        cy.button("Save changes").click();

        modal().within(() => {
          cy.findByText("Save permissions?");
          cy.contains(
            "readonly will be given access to 4 tables in Sample Dataset.",
          );
          cy.contains(
            "readonly will now be able to write native queries for Sample Dataset.",
          );
          cy.button("Yes").click();
        });

        cy.findByText("Save changes").should("not.exist");

        assertPermissionTable([
          ["Administrators", "Allowed", "Allowed"],
          ["All Users", "No access", "No access"],
          ["collection", "No access", "No access"],
          ["data", "Allowed", "Allowed"],
          ["nosql", "Allowed", "No access"],
          ["readonly", "Allowed", "Allowed"],
        ]);
      });
    });
  });
});

function selectSidebarItem(item) {
  cy.findAllByRole("menuitem")
    .contains(item)
    .click();
}

function assertSidebarItems(items) {
  cy.findAllByRole("menuitem").each(($menuItem, index) =>
    cy.wrap($menuItem).should("have.text", items[index]),
  );
}

function modifyPermission(
  item,
  permissionIndex,
  value,
  shouldPropagate = null,
) {
  getPermissionRowPermissions(item)
    .eq(permissionIndex)
    .click();

  popover().within(() => {
    if (shouldPropagate !== null) {
      cy.findByRole("checkbox")
        .as("toggle")
        .then($el => {
          if ($el.attr("aria-checked") !== shouldPropagate.toString()) {
            cy.get("@toggle").click();
          }
        });
    }
    cy.findByText(value).click();
  });
}

function getPermissionRowPermissions(item) {
  return cy
    .get("tbody > tr")
    .contains(item)
    .closest("tr")
    .findAllByTestId("permissions-select");
}

function assertPermissionTable(rows) {
  cy.get("tbody > tr").should("have.length", rows.length);

  rows.forEach(row => {
    const [item, ...permissions] = row;

    getPermissionRowPermissions(item).each(($permissionEl, index) => {
      cy.wrap($permissionEl).should("have.text", permissions[index]);
    });
  });
}

function checkAdministratorsHaveAccessToEverything() {
  cy.findAllByTestId("permissions-select").each($permissionSelect => {
    cy.wrap($permissionSelect)
      .should("have.text", "Allowed")
      .trigger("mouseenter");

    popover().within(() => {
      cy.findByText(
        "Administrators always have the highest level of access to everything in Metabase.",
      );
    });

    cy.wrap($permissionSelect).trigger("mouseleave");
  });
}
