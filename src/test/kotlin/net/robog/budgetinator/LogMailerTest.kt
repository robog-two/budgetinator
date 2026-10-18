package net.robog.budgetinator

import kotlin.test.*

class LogMailerTest {
    @Test fun `apple mail is assumed when no mailto handler is set`() {
        assertTrue(LogMailer.mailHandlerIsAppleMail("""{"LSHandlers":[{"LSHandlerURLScheme":"https","LSHandlerRoleAll":"com.google.chrome"}]}"""))
        assertTrue(LogMailer.mailHandlerIsAppleMail("not json"))
    }

    @Test fun `other mail apps are detected`() {
        assertFalse(LogMailer.mailHandlerIsAppleMail("""{"LSHandlers":[{"LSHandlerURLScheme":"mailto","LSHandlerRoleAll":"com.microsoft.outlook"}]}"""))
        assertTrue(LogMailer.mailHandlerIsAppleMail("""{"LSHandlers":[{"LSHandlerURLScheme":"mailto","LSHandlerRoleAll":"com.apple.Mail"}]}"""))
    }

    @Test fun `mailto has recipient subject and encoded body`() {
        val u = LogMailer.mailtoUri("a b&c\nd").toString()
        assertTrue(u.startsWith("mailto:sam@robog.net?subject=Budgetinator%20Crash&body="))
        assertContains(u, "a%20b%26c%0Ad")
    }
}
