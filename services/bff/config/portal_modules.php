<?php

/*
 * Which part of the portal serves each BFF API endpoint. tests/Feature/PortalCoverageTest fails if a contract endpoint is in neither list, so an API cannot be added
 * without a screen for it.
 *
 *  native  : the endpoint is used by a purpose-built page; the value is the page (and what it is called).
 *  modules : the "Settings and tools" area (/app/manage). Every endpoint listed here gets a card with a plain-language title, a form built from the API's own
 *            field rules, and a button; lists and records are shown as tables, not JSON.
 */
return [
    'modules' => [
        'employers' => ['Employers', 'Change an employer\'s details, link a parent company, and set its options.'],
        'policies' => ['Policy setup', 'Create a policy and set its rates, family rules, contributions, rounding and mid-term rules.'],
        'deposits' => ['Deposits', 'Statements, low-balance alert, top-up requests and shared deposit pools.'],
        'uploads' => ['Uploads and files', 'Check a file before sending it, save column mappings, and close an upload.'],
        'tpa' => ['TPA connections', 'Set up how each TPA is reached, run a reconciliation, and retry sync jobs.'],
        'reports' => ['Reports and activity', 'Recent activity, balances and downloadable reports.'],
        'messages' => ['Announcements', 'Edit an announcement.'],
        'people' => ['Users and branches', 'Add people who can sign in, and manage branches.'],
        'assistant' => ['Assistant documents', 'The documents the assistant answers from.'],
        'account' => ['My account', 'Change your password.'],
        'qa' => ['Test tools', 'Only on test systems: build sample sheets.'],
    ],

    'endpoints' => [
        // module, title, help
        'employers.get' => ['employers', 'See an employer', 'Everything we hold about one employer.'],
        'employers.children' => ['employers', 'See its subsidiaries', 'The companies that sit under this employer.'],
        'employers.update' => ['employers', 'Change an employer\'s details', 'Fill in only what should change.'],
        'employers.deactivate' => ['employers', 'Deactivate an employer', 'The employer stops appearing in lists. Its history is kept.'],
        'employers.set_parent' => ['employers', 'Put under a parent company', 'Choose the parent employer.'],
        'employers.clear_parent' => ['employers', 'Remove from a parent company', 'The employer becomes independent again.'],
        'employers.configs' => ['employers', 'See an employer\'s options', 'The settings that apply to this employer.'],
        'employers.config_put' => ['employers', 'Set an employer option', 'Choose the option name and give it a value.'],
        'employer.me' => ['employers', 'My company', 'The details of the company you work for.'],
        'employer.children' => ['employers', 'My subsidiaries', 'The companies under yours.'],

        'policies.create' => ['policies', 'Create a policy', 'A new policy for an employer. It must be approved before it can be used.'],
        'policies.deactivate' => ['policies', 'Deactivate a policy', 'Stops new endorsements on this policy.'],
        'policies.rate_card' => ['policies', 'See the rate card', 'The premium rates currently set.'],
        'policies.rate_card_put' => ['policies', 'Set the rate card', 'Replaces the premium rates. Load the current ones first.'],
        'policies.family_construct' => ['policies', 'See the family rules', 'Which relations are covered and how many.'],
        'policies.family_construct_put' => ['policies', 'Set the family rules', 'Which relations are covered and how many.'],
        'policies.relation_rules' => ['policies', 'See the relation rules', 'Age and cover rules for each relation.'],
        'policies.relation_rules_put' => ['policies', 'Set the relation rules', 'Age and cover rules for each relation.'],
        'policies.contributions' => ['policies', 'See who pays what', 'How the premium is split between the employer and the employee.'],
        'policies.contributions_put' => ['policies', 'Set who pays what', 'How the premium is split between the employer and the employee.'],
        'policies.rounding' => ['policies', 'See the rounding rule', 'How premiums are rounded.'],
        'policies.rounding_put' => ['policies', 'Set the rounding rule', 'Whether premiums are rounded, and to what.'],
        'policies.midterm' => ['policies', 'See mid-term rules', 'Rules for people who join during the policy year.'],
        'policies.midterm_put' => ['policies', 'Set mid-term rules', 'Rules for people who join during the policy year.'],
        'policies.midterm_dependants' => ['policies', 'See mid-term dependant rules', 'Which dependants can be added during the year.'],
        'policies.midterm_dependants_put' => ['policies', 'Set mid-term dependant rules', 'Which dependants can be added during the year.'],
        'policies.family_validate' => ['policies', 'Check a family against the rules', 'Enter a family and see whether the policy accepts it.'],
        'policies.quote' => ['policies', 'Work out a premium', 'Enter a person and see what the policy would charge.'],
        'policies.enrollment_confirm' => ['policies', 'Confirm an employee for enrolment', 'Mark an employee as confirmed in the enrolment window.'],
        'policies.enrollment_unconfirm' => ['policies', 'Take back a confirmation', 'Removes the confirmation for one employee.'],
        'policies.enrollment_employee_delete' => ['policies', 'Remove an employee from the enrolment window', 'The employee is no longer in the window.'],

        'cd.statements' => ['deposits', 'Deposit statement', 'Every credit and debit on a policy\'s deposit.'],
        'cd.threshold' => ['deposits', 'Set the low-balance alert', 'You are warned when the deposit falls below this amount.'],
        'cd.replenishments' => ['deposits', 'Top-up requests', 'Requests to add money to a deposit.'],
        'cd.replenishment_approve' => ['deposits', 'Approve a top-up request', 'Records the request as approved.'],
        'cd.replenishment_reject' => ['deposits', 'Reject a top-up request', 'Records the request as rejected.'],
        'cd.pools_create' => ['deposits', 'Create a shared deposit pool', 'Several policies can draw on one balance.'],
        'cd.pools_get' => ['deposits', 'See a pool', 'The policies in a pool and its balance.'],
        'cd.pools_add' => ['deposits', 'Add a policy to a pool', 'The policy starts drawing on the pool.'],
        'cd.pools_remove' => ['deposits', 'Take a policy out of a pool', 'The policy goes back to its own balance.'],

        'endorsements.close' => ['uploads', 'Close an upload', 'Marks a finished upload as closed.'],
        'endorsements.csv_preview' => ['uploads', 'Check a file before sending it', 'Shows how the columns will be read, without sending anything.'],
        'endorsements.mappings' => ['uploads', 'Saved column mappings', 'Mappings you have saved for files from your HR system.'],
        'endorsements.mappings_create' => ['uploads', 'Save a column mapping', 'So the next file with the same columns is read the same way.'],
        'endorsements.mappings_update' => ['uploads', 'Change a column mapping', 'Update a saved mapping.'],

        'tpa.configs' => ['tpa', 'See TPA connections', 'How each TPA is reached.'],
        'tpa.configs_create' => ['tpa', 'Add a TPA connection', 'Tell us how to reach a TPA.'],
        'tpa.configs_update' => ['tpa', 'Change a TPA connection', 'Update how a TPA is reached.'],
        'tpa.reconcile_settings' => ['tpa', 'Set automatic reconciliation', 'How often we compare our list with the TPA\'s.'],
        'tpa.status' => ['tpa', 'TPA status', 'Which TPAs are working.'],
        'tpa.reconcile' => ['tpa', 'Reconcile a policy now', 'Compares our member list with the TPA\'s for one policy.'],
        'tpa.reconcile_runs' => ['tpa', 'Past reconciliations', 'Every reconciliation that has run.'],
        'tpa.reconcile_run' => ['tpa', 'See one reconciliation', 'What one reconciliation found.'],
        'sync_jobs.list' => ['tpa', 'Sync jobs', 'The jobs sending members to TPAs.'],
        'sync_jobs.get' => ['tpa', 'See a sync job', 'The state of one job.'],
        'sync_jobs.retry' => ['tpa', 'Retry a sync job', 'Puts a failed job back in the queue.'],

        'read.activity' => ['reports', 'Endorsement activity', 'How many endorsements, by day.'],
        'read.jobs' => ['reports', 'Recent uploads', 'The latest uploads across all policies.'],
        'read.job' => ['reports', 'One upload\'s timeline', 'Every step an upload went through.'],
        'read.cd_balances' => ['reports', 'Deposit balances', 'Every deposit, with the low ones first.'],
        'reports.download' => ['reports', 'Download a report', 'Pick a report and a date range; you get a spreadsheet.'],

        'announcements.update' => ['messages', 'Edit an announcement', 'Change the title, text or dates.'],

        'users.create_broker' => ['people', 'Add a person to your firm', 'They get an email address and a role.'],
        'users.create_employer' => ['people', 'Add a person to your company', 'They get an email address and a role.'],
        'branches.list' => ['people', 'Branches', 'Your firm\'s branches.'],
        'branches.create' => ['people', 'Add a branch', 'A new branch.'],

        'rag.knowledge' => ['assistant', 'Documents the assistant knows', 'The documents it answers from.'],
        'rag.knowledge_create' => ['assistant', 'Add a document', 'Paste policy terms or rules for the assistant to use.'],
        'rag.knowledge_delete' => ['assistant', 'Remove a document', 'The assistant stops using it.'],

        'auth.change_password' => ['account', 'Change my password', 'You will be asked to sign in again.'],

        'sheets.spec' => ['qa', 'What a sample sheet needs', 'The columns a policy\'s sheet has.'],
        'sheets.generate' => ['qa', 'Build a sample sheet', 'Test data only.'],
        'sheets.file' => ['qa', 'Open a sample sheet file', 'Shows what a sample sheet contains.'],
        'sheets.validate' => ['qa', 'Check a sample sheet', 'Checks a sample sheet against the policy.'],
    ],

    'native' => [
        'auth.login' => ['/login', 'Sign in'], 'auth.logout' => ['/app', 'Sign out'], 'auth.me' => ['/app', 'Your name and role in the page header'], 'auth.refresh' => ['/app', 'Keeps you signed in'],
        'employers.list' => ['/app/employers', 'Employers'], 'employers.create' => ['/app/employers', 'Employers: add an employer'],
        'policies.list' => ['/app/policies', 'Policies'], 'policies.get' => ['/app/policies', 'Policy page'], 'policies.approve' => ['/app/policies', 'Policy page: Approve'],
        'policies.readiness' => ['/app/policies', 'Policy page: what is missing'],
        'policies.enrollment_window' => ['/app/policies', 'Policy page: enrolment window'], 'policies.enrollment_window_put' => ['/app/policies', 'Policy page: enrolment window'],
        'policies.enrollment_employees' => ['/app/policies', 'Policy page: enrolment window'], 'policies.enrollment_employees_put' => ['/app/policies', 'Policy page: enrolment window'],
        'policies.enrollment_evaluate' => ['/app/policies', 'Policy page: enrolment window'], 'policies.enrollment_audit' => ['/app/policies', 'Policy page: enrolment window history'],
        'cd.accounts' => ['/app/cd', 'Deposits'], 'cd.account' => ['/app/cd', 'Deposits'], 'cd.credit' => ['/app/cd', 'Deposits: record money received'], 'cd.replenish' => ['/app/cd', 'Deposits: ask for a top-up'],
        'endorsements.list' => ['/app/endorsements', 'Endorsements'], 'endorsements.get' => ['/app/endorsements', 'Upload page'], 'endorsements.audit' => ['/app/endorsements', 'Upload page: history'],
        'endorsements.upload' => ['/app/endorsements', 'Endorsements: upload a file'], 'endorsements.cancel' => ['/app/endorsements', 'Upload page: Cancel'],
        'endorsements.proceed' => ['/app/endorsements', 'Upload page: Proceed'], 'endorsements.retry' => ['/app/endorsements', 'Upload page: Retry'],
        'endorsements.progress' => ['/app/endorsements', 'Upload page: live progress'], 'endorsements.errors' => ['/app/endorsements', 'Upload page: problems found'],
        'endorsements.errors_download' => ['/app/endorsements', 'Upload page: Download as a spreadsheet'],
        'endorsements.intake_plan' => ['/app/endorse', 'Add or remove people'], 'endorsements.intake_template' => ['/app/endorse', 'Add or remove people: download the template'],
        'gui.create' => ['/app/endorse', 'Add or remove people: the grid'], 'gui.get' => ['/app/endorse', 'the grid'], 'gui.validate' => ['/app/endorse', 'the grid'], 'gui.row_put' => ['/app/endorse', 'the grid'],
        'gui.row_delete' => ['/app/endorse', 'the grid'], 'gui.submit' => ['/app/endorse', 'the grid: Submit'], 'gui.grid_validate' => ['/app/endorse', 'the grid'], 'gui.grid_save' => ['/app/endorse', 'the grid'],
        'gui.grid_row_put' => ['/app/endorse', 'the grid'], 'gui.grid_row_delete' => ['/app/endorse', 'the grid'], 'gui.abandon' => ['/app/endorse', 'the grid'],
        'read.dashboard' => ['/app', 'Dashboard'], 'read.red_flags' => ['/app', 'Dashboard: things that need attention'],
        'announcements.list' => ['/app/announcements', 'Announcements'], 'announcements.create' => ['/app/announcements', 'Announcements: new'], 'announcements.retire' => ['/app/announcements', 'Announcements: retire'],
        'offline.board' => ['/app/member-ids', 'TPA member ids'], 'offline.waiting' => ['/app/member-ids', 'TPA member ids: who is waiting'], 'offline.template' => ['/app/member-ids', 'TPA member ids: download the template'],
        'offline.upload' => ['/app/member-ids', 'TPA member ids: upload the TPA\'s sheet'], 'offline.uploads' => ['/app/member-ids', 'TPA member ids: past uploads'], 'offline.upload_detail' => ['/app/member-ids', 'TPA member ids: one upload'],
        'rag.ask' => ['/app/assistant', 'Assistant'],
    ],
];
