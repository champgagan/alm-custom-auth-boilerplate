package com.adobe.alm.customauth.servlets;

import com.adobe.learning.core.services.CPTokenService;
import com.adobe.learning.core.services.GlobalConfigurationService;
import com.day.cq.wcm.api.Page;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javax.servlet.Servlet;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletResponse;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Customer-owned override of the ALM reference implementation's admin
 * refresh-token login
 * servlet
 * ({@code com.adobe.learning.core.servlets.AlmAdminRefreshTokenLoginServlet}).
 *
 * <p>
 * This bundle is deployed alongside (not instead of) the ALM reference site
 * bundle. Sling
 * resolves the winning servlet by OSGi {@code service.ranking} when several
 * servlets register for
 * the same {@code resourceType}/selector/extension/method combination
 * ({@code sling/servlet/default}, {@code adminRefreshToken}, {@code html},
 * {@code POST}). Because
 * {@link #SERVICE_RANKING} is higher than the reference servlet's default
 * ranking (0), this
 * servlet is invoked instead, without any change to the reference project.
 */
@Component(service = Servlet.class, property = {
    "sling.servlet.methods=POST",
    "sling.servlet.resourceTypes=" + CustomAuthLoginServlet.RESOURCE_TYPE,
    "sling.servlet.selectors=" + CustomAuthLoginServlet.SELECTOR,
    "sling.servlet.extensions=" + CustomAuthLoginServlet.EXTENSION,
    "service.ranking:Integer=" + CustomAuthLoginServlet.SERVICE_RANKING
})
@Designate(ocd = CustomAuthLoginServlet.Config.class)
public class CustomAuthLoginServlet extends SlingAllMethodsServlet {

  private static final long serialVersionUID = 1L;

  static final String RESOURCE_TYPE = "sling/servlet/default";
  static final String SELECTOR = "adminRefreshToken";
  static final String EXTENSION = "html";

  /**
   * Must stay higher than the ALM reference servlet's (default, unranked)
   * registration.
   */
  static final int SERVICE_RANKING = 100;

  /**
   * Response flag the ALM front-end checks to confirm a login implementation is
   * active.
   */
  private static final String PRIME_LOGIN_IMPLEMENTATION = "isALMLoginImplementation";

  /**
   * ALM cloud-configuration property names, mirrored from {@code
   * com.adobe.learning.core.utils.Constants.Config} in the reference site's core
   * bundle. That
   * class lives in the {@code com.adobe.learning.core.utils} package, which the
   * reference core
   * bundle does NOT export (see its {@code Export-Package} instructions) — only
   * {@code .services},
   * {@code .models}, {@code .entity} and {@code .servlets} are exported.
   * Importing {@code Constants}
   * here would compile fine locally but fail to resolve at OSGi runtime, so the
   * property names are
   * duplicated as literals instead.
   */
  private static final String CONFIG_ALM_BASE_URL = "almBaseURL";

  private static final String CONFIG_CLIENT_ID = "clientId";
  private static final String CONFIG_CLIENT_SECRET = "clientSecret";
  private static final String CONFIG_ADMIN_REFRESH_TOKEN = "adminRefreshToken";

  /**
   * Despite its Java constant name in the reference project
   * ({@code NOT_STORE_TOKEN_IN_COOKIE}),
   * this cloud-config property controls cookie accessibility, not whether the
   * cookie is set at
   * all: when its value is the string {@code "true"}, the access-token cookie is
   * marked
   * HttpOnly (not readable from client-side JS); otherwise (absent, or explicitly
   * {@code "false"})
   * it is left as a normal, script-readable cookie.
   */
  private static final String CONFIG_STORE_TOKEN_IN_COOKIE = "storeTokenInCookie";

  private static final String ACCESS_TOKEN_COOKIE_NAME = "alm_cp_token";

  private static final Integer TOKEN_BUFFER_SECS = 60;

  private static final Logger LOGGER = LoggerFactory.getLogger(CustomAuthLoginServlet.class);

  @Reference
  private transient GlobalConfigurationService configService;

  @Reference
  private transient CPTokenService tokenService;

  @ObjectClassDefinition(name = "ALM Custom Auth - Login Servlet", description = "Toggle the customer-owned admin refresh-token login override.")
  @interface Config {
    @AttributeDefinition(name = "Enable custom implementation", description = "When disabled, this servlet falls back to the same response as the ALM reference"
        + " implementation, effectively deferring back to the standard behavior.")
    boolean enabled() default true;
  }

  private volatile boolean enabled = true;

  @Activate
  protected void activate(Config config) {
    this.enabled = config.enabled();
  }

  @Override
  protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) {
    try {
      LOGGER.debug("CustomAuthLoginServlet - Method entry - useCustomAuthentication is set to true");
      JsonObject responseObject = new JsonObject();
      responseObject.addProperty(PRIME_LOGIN_IMPLEMENTATION, true);

      if (enabled) {
        applyCustomAuthLogic(request, response, responseObject);
      }

      response.getWriter().write(responseObject.toString());
      response.setStatus(HttpServletResponse.SC_OK);
    } catch (Exception e) {
      LOGGER.error("CustomAuthLoginServlet: exception while handling admin refresh-token login", e);
    }
  }

  /**
   * Silently authenticates the already AEM/IdP-authenticated learner against ALM
   * using the
   * configured admin refresh token, and stores the resulting learner access token
   * in the {@code
   * alm_cp_token} cookie so the ALM V3 widgets can use it without another SSO
   * redirect.
   *
   * <p>
   * On success this flips {@link #PRIME_LOGIN_IMPLEMENTATION} to {@code false} so
   * the front-end
   * (see {@code site-login-impls.js#checkALMLoginImpl}) skips its normal
   * OAuth-redirect login flow.
   * On any failure it is left as {@code true}, so the front-end falls back to the
   * standard ALM
   * login redirect.
   */
  private void applyCustomAuthLogic(
      SlingHttpServletRequest request, SlingHttpServletResponse response, JsonObject responseObject) {
    Page currentPage = getCurrentPage(request, request.getParameter("pagePath"));
    if (currentPage == null) {
      LOGGER.error("CustomAuthLoginServlet: unable to resolve current page to look up ALM configs");
      return;
    }

    JsonObject jsonConfigs = configService.getAdminConfigs(currentPage);
    String almBaseUrl = getConfigValue(jsonConfigs, CONFIG_ALM_BASE_URL);
    String clientId = getConfigValue(jsonConfigs, CONFIG_CLIENT_ID);
    String clientSecret = getConfigValue(jsonConfigs, CONFIG_CLIENT_SECRET);
    String adminRefreshToken = getConfigValue(jsonConfigs, CONFIG_ADMIN_REFRESH_TOKEN);

    if (isBlank(almBaseUrl) || isBlank(clientId) || isBlank(clientSecret) || isBlank(adminRefreshToken)) {
      LOGGER.error(
          "CustomAuthLoginServlet: almBaseURL/clientId/clientSecret/adminRefreshToken are not"
              + " fully configured on the ALM cloud configuration");
      return;
    }

    // TODO: replace with the customer's own lookup of the logged-in learner's email
    // address,
    // e.g. an IdP-asserted SAML/OIDC attribute or a custom session attribute.
    // Defaults to the
    // AEM-authenticated principal name, which only works if that principal name is
    // the learner's
    // email address.
    String learnerEmail = getLearnerEmail(request);
    if (isBlank(learnerEmail)) {
      LOGGER.error("CustomAuthLoginServlet: unable to determine the logged-in learner's email address");
      return;
    }
    LOGGER.info("CustomAuthLoginServlet: credentials are {}. {}",clientId,clientSecret);
    String learnerTokenResponse = tokenService.fetchLearnerToken(almBaseUrl, clientId, clientSecret, adminRefreshToken,
        learnerEmail);

    if (!containsValidAccessToken(learnerTokenResponse)) {
      LOGGER.error(
          "CustomAuthLoginServlet: failed to fetch a learner access token for {}. Response: {}",
          learnerEmail,
          learnerTokenResponse);
      return;
    }

    JsonObject tokenJson = new Gson().fromJson(learnerTokenResponse, JsonObject.class);
    String accessToken = tokenJson.get("access_token").getAsString();
    int expiresInSecs = tokenJson.get("expires_in").getAsInt();

    boolean storeTokenInCookie = isStoreTokenInCookie(jsonConfigs);
    setAccessTokenCookie(request, response, accessToken, expiresInSecs, storeTokenInCookie);
    responseObject.addProperty(PRIME_LOGIN_IMPLEMENTATION, false);
    LOGGER.info("CustomAuthLoginServlet: successful login");
  }

  private boolean isStoreTokenInCookie(JsonObject jsonConfigs) {
    String configValue = getConfigValue(jsonConfigs, CONFIG_STORE_TOKEN_IN_COOKIE);
    return configValue == null || !"true".equals(configValue);
  }

  /**
   * TODO: replace with the customer's own identity lookup (IdP-asserted
   * principal, SAML/OIDC
   * attribute, custom session attribute, etc).
   */
  private String getLearnerEmail(SlingHttpServletRequest request) {
    return "gaganj@adobe.com";
  }

  private void setAccessTokenCookie(
      SlingHttpServletRequest request,
      SlingHttpServletResponse response,
      String accessToken,
      int expiresInSecs,
      boolean storeTokenInCookie) {
    Cookie tokenCookie = new Cookie(ACCESS_TOKEN_COOKIE_NAME, accessToken);
    tokenCookie.setSecure(request.isSecure());
    tokenCookie.setMaxAge(Math.max(expiresInSecs - TOKEN_BUFFER_SECS, 0));
    tokenCookie.setPath("/");
    if (!storeTokenInCookie) {
      tokenCookie.setHttpOnly(true);
    }
    response.addCookie(tokenCookie);
  }

  private boolean containsValidAccessToken(String accessTokenResp) {
    return accessTokenResp != null
        && accessTokenResp.contains("access_token")
        && accessTokenResp.contains("expires_in");
  }

  private String getConfigValue(JsonObject jsonConfigs, String key) {
    return jsonConfigs != null && jsonConfigs.get(key) != null
        ? jsonConfigs.get(key).getAsString()
        : null;
  }

  private boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private Page getCurrentPage(SlingHttpServletRequest request, String pagePath) {
    if (isBlank(pagePath)) {
      return null;
    }
    Resource pageResource = request.getResourceResolver().resolve(pagePath);
    return pageResource != null ? pageResource.adaptTo(Page.class) : null;
  }
}
