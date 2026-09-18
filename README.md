## 🚨 Disclaimer 🚨

> **⚠️ IMPORTANT:**  
> _alm-custom-auth-boilerplate provide a quick-start code block for using custom authetication with Adobe Learning Manager Reference Site._  
> This package is designed for **customers using ALM reference site components with their own AEM sites**.  
> Upon implementation of the provided codebase, **ongoing maintenance** and **further development** shall be the responsibility of the **implementing party**.

> **By using this package, you agree to these terms.**  
> _Please ensure you understand the responsibilities involved before proceeding._

# ALM Custom Auth Boilerplate

A standalone reference AEM bundle project for customers/partners to override the ALM reference site's
admin refresh-token login servlet
(`com.adobe.learning.core.servlets.AlmAdminRefreshTokenLoginServlet`).

## Compatible reference project

This OSGi bundle is intended to work with the Adobe Learning Manager Reference Site:

<https://github.com/adobe/adobe-learning-manager-reference-site/>

Install this bundle alongside the reference site's AEM project
bundle. It overrides the reference site's admin refresh-token login servlet through a higher
OSGi service ranking; it is not a standalone replacement for the reference site.

## Runtime requirements and behavior

- This custom authentication flow is supported only on the AEM **publisher** instance. Deploy and configure the bundle on the author/publisher that serves the learner-facing site.
- The Adobe Learning Manager client ID configured in the Learning Manager configuration must be enabled in the Learning Manager backend to generate the learner token.(Please talk to ALM team to get this done).
- When the Learning Manager component loads, this servlet performs the login silently in the background. This avoids prompting the learner to sign in again through Adobe SSO.

## How the override works

Sling resolves which servlet handles a request by `resourceType` + selector + extension + HTTP
method. Both servlets register for the exact same combination:

| Property | Value |
|---|---|
| `sling.servlet.resourceTypes` | `sling/servlet/default` |
| `sling.servlet.selectors` | `adminRefreshToken` |
| `sling.servlet.extensions` | `html` |
| `sling.servlet.methods` | `POST` |

When multiple servlets are registered for the same combination, Sling picks the one with the
highest OSGi `service.ranking`. [`CustomAuthLoginServlet`](src/main/java/com/adobe/alm/customauth/servlets/CustomAuthLoginServlet.java)
registers with `service.ranking:Integer=100`, which is higher than the reference servlet's
(unset, default `0`) ranking — so once this bundle is installed, this servlet wins and the
reference one is bypassed automatically. No change to the reference project is required, and
uninstalling/disabling this bundle reverts to the reference behavior.

## Customizing

Implement your authentication logic in `applyCustomAuthLogic(...)` in `CustomAuthLoginServlet`.
The servlet keeps the `isALMLoginImplementation: true` response flag the ALM front-end expects,
and gives you a marked extension point to add cookies, token validation/refresh calls, or extra
response fields.

### Required: implement `getLearnerEmail(...)`

`CustomAuthLoginServlet#getLearnerEmail` is a stub that returns a hardcoded email address. You
**must** override it with your own identity lookup (e.g. an IdP-asserted SAML/OIDC attribute or a
custom session attribute) before deploying this to any real environment — otherwise every learner
will be authenticated against ALM as the same hardcoded user.

## Build

```
mvn clean install
```

Produces `target/alm-custom-auth-boilerplate-1.0.0.jar`, an OSGi bundle.

## Deploy

This is a pure OSGi bundle (no `/apps` content, since it targets the sling-default resource
type) — deploy it any way you deploy bundles in your environment, e.g.:

- Upload directly via the Felix Web Console (`/system/console/bundles`) for local/dev testing.
- On AEM as a Cloud Service, embed the bundle jar under an `install` folder of a content package
  (the same way the reference site's `all` package embeds `aem-learning-core`) and deploy that
  package through your Cloud Manager pipeline.
