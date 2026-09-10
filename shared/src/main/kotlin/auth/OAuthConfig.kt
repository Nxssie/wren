package auth

/**
 * Bundled Google OAuth client, injected by each platform at startup — desktop fills it
 * from the generated `BuildConfig`, Android from its own resources. Users can always
 * override it with `<config dir>/oauth.json`.
 */
object OAuthConfig {
    var clientId: String = ""
    var clientSecret: String = ""

    val isConfigured: Boolean get() = clientId.isNotBlank() && clientSecret.isNotBlank()
}
