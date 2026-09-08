package markera.server

/**
 * The public page Google Play links to for account deletion (`GET /delete-account`). No auth, no assets —
 * it only tells users how to delete, the app and `DELETE /account` do the deleting.
 */
fun deleteAccountPage(contactEmail: String?): String {
    val email = contactEmail?.takeIf { it.isNotBlank() }
    val svFallback = if (email != null) {
        """<p>Om du inte längre kan använda appen (borttappad telefon, avinstallerad app): skicka ett
        e-postmeddelande från det Google-konto du loggade in med, och be om radering, till
        <a href="mailto:$email">$email</a>. Kontot raderas inom 30 dagar.</p>"""
    } else {
        """<p>Om du inte längre kan använda appen (borttappad telefon, avinstallerad app): kontakta
        utvecklaren via den utvecklarkontakt-e-postadress som visas på appens sida på Google Play, från det
        Google-konto du loggade in med. Kontot raderas inom 30 dagar.</p>"""
    }
    val enFallback = if (email != null) {
        """<p>If you can no longer use the app (lost phone, app uninstalled): send an e-mail from the Google
        account you signed in with, asking for deletion, to <a href="mailto:$email">$email</a>. The account
        is deleted within 30 days.</p>"""
    } else {
        """<p>If you can no longer use the app (lost phone, app uninstalled): contact the developer at the
        developer contact e-mail shown on the app's Google Play listing, from the Google account you signed
        in with. The account is deleted within 30 days.</p>"""
    }
    return """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Radera konto – Markera / Delete account – Markera</title><style>
body{background:#12160f;color:#e6ead9;font:16px/1.5 system-ui,sans-serif;margin:24px;max-width:40em}
a{color:#9ccc65}
h1{font-size:20px}
h2{font-size:18px;margin-top:32px}
</style></head><body>
<h1>Radera konto – Markera</h1>
<p>I appen: öppna Markera, tryck på <strong>Radera konto</strong> på startskärmen (Hem) och bekräfta.
Kontot raderas omedelbart.</p>
<p>Det som raderas: kontot, alla sparade serier med sina träffar, alla uppladdade tavelfoton och alla
inloggningar (sessioner). Raderingen sker direkt och är permanent – servern behåller ingenting efteråt.</p>
$svFallback
<h2>Delete account – Markera</h2>
<p>In the app: open Markera, on the Home screen (Hem) tap <strong>Radera konto</strong> and confirm. This
deletes the account immediately.</p>
<p>What is deleted: the account, every saved series with its holes, every uploaded target photo, and all
sessions. Deletion is immediate and permanent — the server keeps nothing afterwards.</p>
$enFallback
</body></html>"""
}
