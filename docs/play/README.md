# Sleepers on Google Play (BACKLOG F35)

Tae's first install comes from Play's **internal testing** track. CI does the rest: every merge to `main` builds a signed
`.aab` and uploads it there (DESIGN §6). Everything below happens on the phone except two steps, which are marked.

## Your question: "Is there no approval required? Can I update often?"

For you, mostly no approval. Internal testing reaches up to 100 testers you name, and Google says a new build is there
"within minutes" and "may not be subject to the usual Play policy or security reviews". The very first release of a
new app is slower: its test link can take a few hours to work, and its store details can show placeholders for up to
48 hours. After that, update as often as you like. Each merge to `main` is a new build with a higher number, and Play
updates your phone like any other app. The approval comes when you go public. A new personal developer account must
first run a **closed test with at least 12 testers, opted in for 14 days in a row**, then apply for production access.
After that, Google reviews every public update, usually within a few days and sometimes up to a week. The internal
track stays review-light for you the whole time.

Checked on 2026-11-23 against Google's help pages for internal testing (answer 9845334), the new-account testing rule
(answer 14151465) and full-screen intents (answer 13392821), through search results: the session's network can't open
support.google.com directly.

## What only you can do (numbered, phone first)

Do them in this order. Each one is a few minutes except step 1, where Google can take days.

1. **Make a Play developer account.** On the phone, open <https://play.google.com/console/signup>, choose
   **Yourself** (a personal account), pay the one-off US$25, and verify your identity: a photo of your ID, your phone
   number, and possibly the **Play Console app** (from Play) to confirm your device. Wait for the "verified" email.
2. **The name and the ID** are **Sleepers** and **`app.sleepers`** (Tae, 2026-11-28). Play Console holds that ID for
   the app, so a bundle with any other ID is refused.
3. **Let GitHub make the upload key** (no laptop, and nobody ever sees the key):
   1. GitHub → your photo → **Settings → Developer settings → Personal access tokens → Fine-grained tokens →
      Generate new token**. Name: `nodstop-key`. Expiration: 1 day. Repository access: **Only select repositories →
      wake-up-call**. Permissions → Repository → **Secrets: Read and write**. Generate, then copy the token.
   2. The repo → **Settings → Secrets and variables → Actions → New repository secret**. Name `SECRETS_TOKEN`,
      paste the token, **Add secret**.
   3. The repo → **Actions → make-release-key → Run workflow**. A green tick means the key is stored as two secrets,
      `RELEASE_KEYSTORE_BASE64` and `RELEASE_KEYSTORE_PASSWORD`. Don't run it again: it refuses, on purpose.
   4. Delete the `SECRETS_TOKEN` secret, and delete the token (same page as 3.1).
4. **Get the first Play build.** Merge any PR, or open **Actions → android → Run workflow** on `main`. When it's
   green, download `app-release.aab` from
   <https://github.com/tyforlife/wake-up-call/releases/download/play-latest/app-release.aab>.
5. **Create the app in Play Console** (works in the phone browser; tap **Desktop site** if a page is cramped):
   **Create app** → name "Sleepers", English, **App**, **Free**, tick the declarations → **Create app**.
6. **First release, by hand** (Play's API can't create an app, so this one upload is yours):
   1. **Test and release → Testing → Internal testing → Testers**: create an email list with your own Gmail. Save.
   2. **Releases → Create new release**. If asked about app signing, keep **Google-generated key**. Upload the
      `app-release.aab` from step 4. **Next → Save and publish**.
   3. Fill in what the dashboard asks for under **App content**, copying from `docs/play/declarations.md`
      (privacy policy link, data safety, ads, content rating, target audience, exact alarms, full-screen alarm,
      foreground services).
7. **Let CI upload every merge** (step 7.1 is easier on a laptop; it works on a phone in desktop mode):
   1. <https://console.cloud.google.com> → create a project "nodstop" → search **Google Play Android Developer
      API** → **Enable**.
   2. **IAM & Admin → Service accounts → Create service account**, name `github-ci`, **Done**. Open it → **Keys →
      Add key → Create new key → JSON**. A `.json` file downloads.
   3. Play Console → **Users and permissions → Invite new users** → paste the service account's email (it ends in
      `iam.gserviceaccount.com`) → **App permissions → Sleepers → Release to testing tracks** → **Invite**.
   4. The repo → **New repository secret** `PLAY_SERVICE_ACCOUNT_JSON`: open the `.json` file, select all, copy,
      paste. Then delete the file from the phone's Downloads.
8. **Install from Play.**
   1. In the Sleepers you use today: **Settings → Debug → Your data → Back up to a file**, and save the file.
   2. Uninstall that Sleepers. It was signed with the debug key, so Play can't update it.
   3. Play Console → **Internal testing → Testers → Copy link**. Open it on the phone, **Accept**, **Install**.
   4. In the new Sleepers: **Settings → Debug → Your data → Restore from a file**. Allow the Setup rows again.

From then on, each merge to `main` shows up as a Play update. The `debug-latest` APK keeps working, as a separate app
called **Sleepers dev** (ID `app.sleepers.debug`), so you can try a PR without touching your Play install.

## Later: going public

1. **Closed testing**: add at least 12 testers (Gmail addresses or a Google Group) and keep them opted in for 14 days
   in a row. Recruit a few spare in case someone drops out.
2. **Apply for production access** from the dashboard. It isn't automatic once the 14 days pass.
3. Then the rest of DESIGN §8 "Public release": the other ABIs, AT's permission, other phone brands' battery steps.

## The secrets (exact names)

| Secret | What | Made by |
| --- | --- | --- |
| `RELEASE_KEYSTORE_BASE64` | The upload key (PKCS12, alias `upload`) | make-release-key, step 3 |
| `RELEASE_KEYSTORE_PASSWORD` | Its password | make-release-key, step 3 |
| `PLAY_SERVICE_ACCOUNT_JSON` | The service account's JSON key | You, step 7 |
| `SECRETS_TOKEN` | Lets make-release-key write the two above; delete after | You, step 3 (temporary) |

Until a secret exists, its CI step is skipped and CI stays green. Without the upload key, the Play build is still made
but left unsigned. Without `PLAY_SERVICE_ACCOUNT_JSON`, the signed `.aab` still goes to `play-latest`, but nothing goes
to Play.

**If the upload key is ever lost or leaked:** Play App Signing holds the real app key, so the app is safe. Delete the
two `RELEASE_*` secrets, run make-release-key again, then Play Console → **Test and release → App signing →
Request upload key reset**, with the new certificate.

## Name and ID (DESIGN §9)

**Sleepers**, application ID **`app.sleepers`** (Tae, 2026-11-28). The ID is permanent on Play. The name can change in
Play Console and in `app/src/main/res/values/strings.xml` (`app_name`). CI reads the ID from `app/build.gradle.kts`.
The code keeps its package `app.nodstop`, which users never see.

## Store pictures

`icon-512.png` (the app icon, 512 x 512) and `feature-graphic.png` (1024 x 500) are made from the app's icon by
`node docs/design/store.js`. Upload them under **Grow users → Store presence → Main store listing → Graphics**.
