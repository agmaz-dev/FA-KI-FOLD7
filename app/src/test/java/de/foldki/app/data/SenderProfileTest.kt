package de.foldki.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SenderProfileTest {

    @Test fun initialsFromName() {
        assertEquals("PF", SenderProfile.initialsOf("Paula Fischer"))
        assertEquals("MM", SenderProfile.initialsOf("Dr. Max von Muster"))
        assertEquals("EM", SenderProfile.initialsOf("Erika Maria Mustermann"))
        assertEquals("MU", SenderProfile.initialsOf("Muster"))
        assertEquals("", SenderProfile.initialsOf(""))
    }

    @Test fun manualInitialsWin() {
        val p = SenderProfile(name = "Paula Fischer")
        assertEquals("PF", p.effectiveInitials)
        assertEquals("FAg", p.copy(initials = "FAg", initialsManual = true).effectiveInitials)
        assertEquals("PF", p.copy(initials = "", initialsManual = true).effectiveInitials)
    }

    @Test fun migrationIsLossless() {
        val old = "Paula Fischer\nLindenweg 3\n10115 Berlin\nTel. 030 1234567\npaula@example.org\nKundennr. 4711\nzweite Zeile"
        val p = SenderProfile.fromLegacy(old)
        assertEquals("Paula Fischer", p.name)
        assertEquals("Lindenweg 3", p.street)
        assertEquals("10115 Berlin", p.zipCity)
        assertEquals("Tel. 030 1234567", p.phone)
        assertEquals("paula@example.org", p.email)
        assertEquals("Kundennr. 4711\nzweite Zeile", p.extra)
        assertEquals("Berlin", p.city)
        assertEquals(old, p.toLegacyText())
    }

    @Test fun promptBlockSkipsEmptyFieldsAndHasInitials() {
        val p = SenderProfile(label = "Privat", name = "Paula Fischer", street = "Lindenweg 3", zipCity = "10115 Berlin")
        assertEquals("Absender (aktiv):\nPaula Fischer\nLindenweg 3\n10115 Berlin\nInitialen: PF", SenderProfile.promptBlock(p))
        assertFalse(SenderProfile.promptBlock(p).contains("Privat"))
    }

    @Test fun jsonRoundTrip() {
        val list = listOf(
            SenderProfile(label = "Privat", name = "Paula Fischer", zipCity = "10115 Berlin"),
            SenderProfile(label = "Firma", name = "Fischer GmbH", extra = "USt-IdNr. DE123", initials = "AG", initialsManual = true),
        )
        val back = SenderProfile.listFromJson(SenderProfile.listToJson(list))
        assertEquals(list, back)
        assertTrue(SenderProfile.listFromJson("kaputt").isEmpty())
    }
}
