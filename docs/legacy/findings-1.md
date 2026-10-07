# Legacy code findings, pass 1

Read so far: `Kernel.php` (all), `premium_helper.php` (lines 1-585, 5156-5220, 6900-6922 of 6922), `tpa_helper.php` (auth/token functions, function index),
`TestController.php` (function index, `getCareEnrollData`, `assignTpaMemberId`), `broker_api.php` (endorsement routes only), function indexes of
`helper.php` and `suminsured_helper.php`. **Not yet read**: the other ~6,000 lines of premium raters, `suminsured_helper.php`, most of `helper.php`,
`admin_api.php`, `employee_api.php`, the per-TPA response mappings (`tpaMapping`, `tpaFlagMapping`). Anything below is what those lines say, nothing more.

## 1. Pro-rata (compares with `apps/computation/pro_rata.py`)
| Legacy behaviour (source) | Mine | Verdict |
|---|---|---|
| Joiner premium = annual premium x days from cover start to policy end, **both inclusive**, over policy days start..end inclusive (`calculatePremiumWithContribution`) | `EXACT_DAYS`: remaining inclusive / total inclusive | **Matches** |
| Mid-term change: old premium for cover start..correction date (**correction day excluded**) + new premium from correction date..end (**included**) (`proRataCalculation`, `dayWisePremium`) | family after-minus-before times remaining-inclusive/total | **Equivalent** for a member whose cover starts at policy start |
| Age is `dob.diffInYears(cover start date)` | `age_on(dob, effective_date)` | **Matches** |
| Pro-rata type is **per relation per policy** (`policy_age_details.prorata_type_id`), DAILY or MONTHLY; QUARTERLY and HALF_YEARLY are `TODO` in legacy | one type per policy | **Differs**: needs a per-relation setting |
| MONTHLY = `ceil(float months)` based, e.g. months covered = ceil(months from policy start to leaving) - floor(months from policy start to cover start) | `FULL_MONTH` = calendar-month counting | **Differs**: my FULL_MONTH is not the legacy rule |
| Removal refund = premium - (days from cover start to **leaving date inclusive**) x per-day; per-day uses days from cover start to policy end inclusive. A setting (`removal_day_refund_premium_calculation`, off for TATA AIG) changes the leaving-day and same-day-removal treatment | refund from the effective date inclusive | **Differs / ambiguous**: if our REMOVE effective date is the leaving date, I refund one day too many |
| Employer/employee **contribution split**, by percentage or fixed value, pro-rated too | not modelled | **Missing** |
| Rounding: only EDME_PROD rounds (to integer); all others unrounded here | always 2 dp | Needs a decision |
| No-rule policies take `employee_premium` + `employer_premium` straight from the uploaded row | none | **Missing** |

## 2. Premium raters
Legacy `calculatePolicyMemberPremiumAmounts` is one 6,200-line function: for each of IPD and OPD it branches on ~25 premium types
(employee age, member age, grade, grade with exclusion on number of parents, designation (+exclusion), per-mille by age / grade / designation,
member-wise, state-wise, family-construct-wise, grade / new-grade / designation / LOL-grade with construct, relation-wise salary multiple per-mille, flat, no-rule)
against `policy.rates` rows with columns like `age` ("18-25", inclusive, first match), `age_suminsured`, `flat_suminsured`, `total_premium`,
`is_opd_rate`, `is_enchance_flex_rate`; age-band lookup falls back to rows without a sum-insured match. It also carries parent capping flags and
`ipd/opd additional premium`. My 17 rater classes are a different decomposition; I have verified only FLAT and the two age types against legacy so far.

## 3. TPA integration is mostly PULL, not push (affects integration-service)
- Legacy fetches each policy's **active lives from the TPA** (`getCareEnrollData`, `getParamountEnrollData`, `getSafewayEnrollData`, ...) and **reconciles** them onto
  our members (`assignTpaMemberId`, `addMemberData`): match by employee code (trimmed, lower-cased, leading zeros stripped for Care) + relation via
  `tpa_relation_mapping`, with a fuzzy first-name check for spouse/child; write `tpa_member_id` / `tpa_member_name`; misses go to an error report ("failed dump").
  Field names in each TPA's response come from `master_tpa_mapping`.
- Outbound pushes exist for a few (ICICI endorsement commands, Care endorsement data/status commands, SBI), and endorsements go to **insurers** through the
  queue's "sent-data-to-insurer" step. Care endorsement status is polled 4x/day.
- Connection config is **per policy** (`policy.tpaServices`: name such as GenerateToken / GetBrokerClientDetails / ActiveLives, url, method, headers,
  username, password, broker_code), not per broker+TPA. My `tpa_configs` is the wrong grain.
- Care protocol: partner token call -> `sessionId` + token list; each token string `key|value` is AES-256-CBC encrypted and base64'd into a `tokenId`;
  ActiveLives request is `{apikey, policy_number}` JSON, AES-256-CBC + base64, posted as form field `req_data`; response decrypted the same way.
- Scheduling is cron batches per portal (member-id supervisors at 00:10, ecards ~06:30, network hospitals ~03:30, claim dumps every few hours).

**Implication:** integration-service needs (a) a pull-and-reconcile mode with per-TPA response mappings and relation mappings, (b) per-policy service
configs, (c) a scheduler, in addition to the push path I built. The push path is still right for ICICI/Care endorsements.

## 4. Endorsement workflow (from `broker_api.php` routes)
Queue-based: upload-data-dump -> validate-queued-data -> discrepancy update / upload discrepancy-resolved-file -> process-queued-data -> endorse-queued-data ->
sent-data-to-insurer (-> ic-report-approval-status-change), plus single-employee, multi-policy, multi-employer and combined endorsements and CD endorsement
configurators. Not yet compared with my state machine.

## 5. Security problems in the legacy code (do not port)
- A fixed AES key and IV are hard-coded in `TestController.php` (the `careEncrypt` / `careDecrypt` defaults) and in `tpa_helper.php`
  (`palmCareEcardEncryptDecrypt`). They are now in files shared in this session: **treat them as compromised, rotate them with the TPA, and keep keys in a secret store.**
- TPA usernames/passwords are read from a database column and used as-is; token requests are logged with the partner id and security key in the payload.
- Test/debug endpoints live in a controller named `TestController` and perform production work.
- A hard-coded internal proxy address and per-portal if/else chains are scattered through the scheduler.

## 6. Questions that block the next port
1. For REMOVE endorsements, is the effective date the **leaving date** (cover ends that day inclusive) or the first uncovered day?
2. Which pro-rata types must exist: DAILY and MONTHLY only (legacy has only those two working)? Per relation per policy?
3. Which TPAs are in scope first (Care, MediAssist, FHPL, Paramount, Palm-Vipul, Safeway ...), and do you have a sandbox or a sample ActiveLives response for each?
4. Should the engine split premium into employee/employer contributions, and who owns that (policy rate card or employer config)?
5. Which premium types does production actually use? Porting all ~25 is large; usage counts would let me start with the live ones.
