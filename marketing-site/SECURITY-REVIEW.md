# Google Safe Browsing review for netflixpro.vercel.app

Google's public Transparency Report showed an active unsafe-site verdict on October 2, 2026: pages that try to trick visitors into sharing personal information or downloading software. This is a Safe Browsing classification, separate from HTTPS. The public report does not identify which exact page or APK triggered the decision.

## Changes made

- Replaced the Netflix N and Netflix-style wordmark in the marketing page and illustrative tours with a distinct project mark and plain project wordmark.
- Added visible developer identity (`mzazimhenga`) and a prominent non-affiliation notice before the download buttons.
- Added an About section with website privacy and free-download versus paid-plan information. The developer credit is mzazimhenga. GitHub profile, source-code and support links were removed from the page at the owner's request; APK files still use the existing project download host.
- Labelled the currently published APKs as **debug review builds**, outside Google Play. Downloads are manual, and hero buttons lead to the section with build context.
- Package, source, file SHA-256 and signing certificate SHA-256 remain in machine-readable update metadata. At the owner's request, the download cards omit these technical details and their repeated developer credit.
- Restricted metadata download URLs to this project's pinned GitHub APKs, project release URLs or same-site APK paths; required build labels and valid signer fingerprints.
- Replaced Netflix-hosted profile avatars with a bundled generic preview asset.
- Moved executable inline code into a same-origin module. Added CSP, frame protection and restricted browser permissions. No password or payment forms, automatic downloads, third-party advertising or analytics scripts were added.
- Excluded the obsolete, unlinked bundled APK and local environment files from Vercel uploads. Retained the existing public debug download metadata; no claim is made that those APKs have been cleared by Google.
- Corrected the local Vercel project link from its stale name to the actual `netflixpro` project.

## Verification

Eleven Node tests passed, covering download-source restrictions, build labels, signer identity, versions and channel separation. JavaScript syntax checks passed. The preview HTTP audit checked thirteen files against local source, verified security headers and confirmed HTTP 404 for the obsolete bundled APK. The final production audit is saved separately in the workspace's `outputs/marketing-deployment-verification.json`.

The source changes were deployed to the existing `https://netflixpro.vercel.app/` address on October 3, 2026, and all thirteen production file/security-header checks passed. The domain was attached to the existing Vercel project for future production deploys.

Browser visual verification and the Google review submission remain pending: the browser tool could not verify its saved access permissions for `search.google.com`. No security interstitial was bypassed. Code deployment does not clear Google's classification.

## Owner steps

1. Open [Search Console Security Issues](https://search.google.com/search-console/security-issues?resource_id=https%3A%2F%2Fnetflixpro.vercel.app%2F) while signed in to the site's owner account.
2. Add/verify the URL-prefix property `https://netflixpro.vercel.app/` if necessary. Use Google's HTML file or HTML meta-tag verification method for this Vercel subdomain. The verification value must come from the owner's Search Console account.
3. Inspect the Security Issues report and its sample URLs. If a specific APK is flagged as harmful, remove that affected download and investigate the binary before requesting review. Do not describe it as a false positive without evidence.
4. Once every reported issue is addressed, select **Request Review** and describe the changes and checks. Submit one request; wait for Google's decision rather than resubmitting repeatedly.

Suggested review text, to adapt after reading the actual report:

> This is an independent Android app project maintained by mzazimhenga, not an official Netflix site. We replaced the Netflix-style marks, made the developer and non-affiliation notice prominent, added website privacy and download-source information, and clearly labelled debug APK downloads. Download links require an explicit click and show version and build type; they are restricted to our project sources. The page has no password or payment forms. We removed the obsolete bundled APK from the deployment and added a restrictive script CSP and other security headers. We checked the deployed files and release metadata. Please review the updated site. We understand that any additional reported harmful download or deceptive page must also be corrected.

The owner subsequently chose **https://npro-app.vercel.app/** as the public marketing address. The page's canonical link uses that address; the old address remains available for existing app update requests. This branding/address change does not clear the old domain's Safe Browsing classification. The security cleanup and review instructions above still apply.

Google's documentation: [Security Issues report](https://support.google.com/webmasters/answer/9044101?hl=en). Google's decision and review timing cannot be controlled by Vercel or this code change.
