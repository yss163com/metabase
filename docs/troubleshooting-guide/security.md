# Managing Security

<div class='doc-toc' markdown=1>
- FIXME
</div>

FIXME: general introduction

- https://metabase.zendesk.com/agent/tickets/5913 explains how to connect Metabase to a firewalled database

- see the note on SQL permissions and using multiple database connections to restrict access in managing-people.md

- re-read https://www.metabase.com/learn/permissions/data-permissions

- point out that we don't yet have fine-grained permissions as per https://github.com/metabase/metabase/issues/6799

- see  https://github.com/metabase/metaboat/issues/101
  - Metabase itself can be given restricted permissions (read only)
  - Redshift creates temporary tables

- https://metabase.zendesk.com/agent/tickets/2123
  - Q: I would like to give my users the ability to see this dashboard. But *NOT* see the underlying questions associated with it, so that I can enforce filtering rules on the set they’re viewing.  I currently have it set so these users can only see this collection, but if I move the question anywhere else, it breaks.  Currently they can navigate to the question if they go looking, and I’d prefer them not to be able to query the entire data set without my restrictions.
  - A: We currently don't have a way to define permissions in that way, but we're looking into more permission levels in future versions. Right now, the workaround would be to create a sub-collection within "Covid Data Analysis", where you put all the questions.
  - A: You set the permissions for the parent collection, which will be passed down to the lower collection as well (unless you manually change that). This doesn't hide the questions, it just makes you able to place them in a sub-collection, so they're not shown together with the dashboards in the parent collection... If you're only sharing a single dashboard for that group of users, have you considered using Embedding or Full App Embedding, so they don't have access to all of Metabase?

- https://metabase.zendesk.com/agent/tickets/2434
  - If a user is granted "View access" to a collection, any questions in that collection will be visible (including the results!) even if the user doesn't have data access. I've gotten a little tripped up by this in the past and it's not entirely clear so just wanted to make sure I mention it again.
  - Need this one explained...

- https://metabase.zendesk.com/agent/tickets/4882
  - Admin can't see subcollections in users' folders
  - It's a bug https://github.com/metabase/metabase/issues/15339: include it or not?

- https://metabase.zendesk.com/agent/tickets/3214
  - What is the "permission graph", why is it a single object, and do we need to explain this?

- https://metabase.zendesk.com/agent/tickets/3041
  - When the user is allowed Data access, then they are given options to modify questions - even if the questions are in a view-only collection - since they can save the question to a collection they have write permissions to.
If you don't want users to be able to modify/create questions, then you need to revoke the Data permissions and only define view-only Collection permissions.

<h2 id="fixme">FIXME</h2>

**How to detect this:** FIXME

**How to fix this:** FIXME
