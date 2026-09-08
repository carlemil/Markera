package markera.server

/** The shell every public page shares: no auth, no assets, one inline stylesheet. */
private fun page(title: String, body: String): String = """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>$title</title><style>
body{background:#12160f;color:#e6ead9;font:16px/1.5 system-ui,sans-serif;margin:24px;max-width:40em}
a{color:#9ccc65}
h1{font-size:20px}
h2{font-size:18px;margin-top:32px}
</style></head><body>
$body
</body></html>"""

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
    return page(
        "Radera konto – Markera / Delete account – Markera",
        """<h1>Radera konto – Markera</h1>
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
$enFallback""",
    )
}

/** The privacy policy (`GET /privacy`), the URL Google Play needs. Swedish first, then the same in English. */
fun privacyPage(contactEmail: String?): String {
    val email = contactEmail?.takeIf { it.isNotBlank() }
    val svContact = if (email != null) {
        """<a href="mailto:$email">$email</a>"""
    } else {
        "den utvecklarkontakt-e-postadress som visas på appens sida på Google Play"
    }
    val enContact = if (email != null) {
        """<a href="mailto:$email">$email</a>"""
    } else {
        "the developer contact e-mail shown on the app's Google Play listing"
    }
    return page(
        "Integritetspolicy – Markera / Privacy policy – Markera",
        """<h1>Integritetspolicy – Markera</h1>
<p>Personuppgiftsansvarig är appens utvecklare, Carl-Emil Kjellstrand, Sverige. Kontakt: $svContact.</p>
<p>Gäller från: 2026-09-08.</p>
<h2 style="font-size:16px">Vad som lagras</h2>
<p>Ingenting lagras på servern om du använder appen utan att logga in. Loggar du in med Google lagras:</p>
<ul>
<li><strong>Konto:</strong> Google-kontots identifierare (<code>sub</code> i inloggningstoken) och ett
visningsnamn (namnet från Google, eller e-postadressen om namn saknas). Inget lösenord ses eller lagras.</li>
<li><strong>Sparade serier:</strong> tidpunkt, kaliber och varje detekterad träffs position och poäng.</li>
<li><strong>Tavelfotot</strong> för varje sparad serie (en beskuren kvadratisk bild från kameran; den kan
visa det som skrivits på tavlan).</li>
<li><strong>Sessioner</strong> (inloggningstoken) så att appen förblir inloggad.</li>
</ul>
<h2 style="font-size:16px">Sådant som bara sker i telefonen</h2>
<p>Träffdetekteringen (ett neuralt nätverk som körs på enheten) och avläsningen av ringsiffrorna
(textigenkänning på enheten, Google ML Kit) sker lokalt. Kamerabilder skickas ingenstans om du inte
sparar en serie.</p>
<h2 style="font-size:16px">Delning, lagringsplats och lagringstid</h2>
<p>Ingen analys, ingen reklam, inga spårnings-SDK:er, ingen försäljning eller delning av uppgifter med
tredje part. Googles inloggningstjänst används enbart för att verifiera vem du är.</p>
<p>Uppgifterna ligger på utvecklarens egen server i Sverige och överförs över HTTPS. De sparas tills du
raderar dem.</p>
<h2 style="font-size:16px">Radering och dina rättigheter</h2>
<p>Radera kontot i appen (Hem → <strong>Radera konto</strong>) eller så som beskrivs på
<a href="/delete-account">sidan om kontoradering</a>. Enskilda serier raderas i appens Historik.</p>
<p>Enligt GDPR har du rätt till tillgång, rättelse, radering och dataportabilitet. Appens Historik har en
export (<strong>Exportera</strong>) som lämnar ut alla dina serier som CSV plus bilderna. Klagomål kan
lämnas till Integritetsskyddsmyndigheten (IMY) i Sverige.</p>
<p>Appen riktar sig inte till barn under 13 år. Ändringar i denna policy publiceras på den här sidan.</p>

<h2>Privacy policy – Markera</h2>
<p>The controller is the app's developer, Carl-Emil Kjellstrand, Sweden. Contact: $enContact.</p>
<p>Effective date: 2026-09-08.</p>
<h2 style="font-size:16px">What is stored</h2>
<p>Nothing is stored on the server if you use the app without signing in. When you sign in with Google,
the following is stored:</p>
<ul>
<li><strong>Account:</strong> the Google account's identifier (the token's <code>sub</code>) and a display
name (the Google name, or the e-mail address when no name is available). No password is ever seen or
stored.</li>
<li><strong>Saved series:</strong> time, caliber, and each detected hole's position and score.</li>
<li><strong>The target photo</strong> of each saved series (a cropped square frame from the camera; it may
show whatever was written on the target).</li>
<li><strong>Session tokens</strong> so the app stays signed in.</li>
</ul>
<h2 style="font-size:16px">Processing that happens only on the phone</h2>
<p>Hole detection (an on-device neural network) and ring-digit reading (on-device text recognition, Google
ML Kit) run locally. Camera frames are not sent anywhere unless you save a series.</p>
<h2 style="font-size:16px">Sharing, location and retention</h2>
<p>No analytics, no advertising, no tracking SDKs, no sale or sharing of data with third parties. Google's
sign-in service is used only to verify who you are.</p>
<p>The data lives on the developer's own server in Sweden and is transferred over HTTPS. It is kept until
you delete it.</p>
<h2 style="font-size:16px">Deletion and your rights</h2>
<p>Delete the account in the app (Home → <strong>Radera konto</strong>) or as described on the
<a href="/delete-account">account deletion page</a>. Individual series can be deleted in the app's
Historik.</p>
<p>Under the GDPR you have the right of access, correction, deletion and portability. The app's Historik
has an export (<strong>Exportera</strong>) that hands over all your series as CSV plus the images.
Complaints can be filed with Integritetsskyddsmyndigheten (IMY) in Sweden.</p>
<p>The app is not directed at children under 13. Changes to this policy are published on this page.</p>""",
    )
}
