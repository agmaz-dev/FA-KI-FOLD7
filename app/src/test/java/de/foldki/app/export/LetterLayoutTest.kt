package de.foldki.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterLayoutTest {
    @Test fun readsAssembledLetter() {
        val text = LetterAnalysis.assemble(
            "Betreff: Widerspruch\n\nSehr geehrte Damen und Herren,\n\nich widerspreche.\n\nMit freundlichen Grüßen",
            sender = "Paula Fischer\nLindenallee 18\n14612 Falkensee",
            recipient = "MKK\nMusterallee 86-90\n20095 Hamburg",
            placeDate = "Falkensee, 16.11.2026", name = "Paula Fischer",
            refs = listOf("Ihr Schreiben vom: 03.11.2026", "Ihr Zeichen: 4711230"),
        )
        val l = LetterLayout.parse(text)!!
        assertEquals("Paula Fischer\nLindenallee 18\n14612 Falkensee", l.sender)
        assertTrue(l.recipient.startsWith("MKK"))
        assertEquals("03.11.2026", l.theirDate)
        assertEquals("4711230", l.reference)
        assertEquals("Falkensee, 16.11.2026", l.placeDate)
        assertEquals("Widerspruch", l.subject)
        assertEquals("Paula Fischer", l.signerName)
        assertTrue(l.body.startsWith("Sehr geehrte") && l.body.endsWith("Mit freundlichen Grüßen"))
    }

    @Test fun plainTextIsNoLetter() {
        assertNull(LetterLayout.parse("Einkaufsliste\n\nMilch, Brot"))
    }
}

class LetterRepairTest {
    @Test fun repairsGarbledNumbers() {
        val t = "Betreff: Widerspruch – Zeichen 47112330\n\nSehr geehrter Herr Beispiel,\nhiermit beziehe ich mich auf Ihr Schreiben vom 12.08.202026 über 5770 Euro. " +
            "Ich zahle die 57,000 Euro nicht. Rückmeldung bis zum 16.1112626. Telefon 030123456789, 20095 Hamburg, 7.00 Euro."
        val out = LetterAnalysis.repairNumbers(t, "57,00 Euro", listOf("12.08.2026", "16.11.2026"), "4711230 und Datum des Schreibens (nicht")
        assertTrue(out, out.contains("Zeichen 4711230\n"))
        assertTrue(out, out.contains("vom 12.08.2026 über 57,00 Euro."))
        assertTrue(out, out.contains("die 57,00 Euro nicht"))
        assertTrue(out, out.contains("bis zum 16.11.2026."))
        assertTrue(out, out.contains("030123456789") && out.contains("20095 Hamburg") && out.contains("7.00 Euro"))
    }

    @Test fun cleansReference() {
        assertEquals("4711230", LetterAnalysis.cleanRef("4711230 und Datum des Schreibens (nicht"))
        assertEquals("40297011", LetterAnalysis.cleanRef("Aktenzeichen 40297011"))
        assertEquals("12/345-6", LetterAnalysis.cleanRef("Ihr Zeichen: 12/345-6"))
    }

    @Test fun putsNameBeforeStreet() {
        val r = LetterMeta.completeRecipient("Musterallee 86-90\n20095 Hamburg", "MKK – Muster Krankenkasse\nKontoauszug", null)
        assertEquals("MKK – Muster Krankenkasse\nMusterallee 86-90\n20095 Hamburg", r)
        assertEquals("Stadt X\nWeg 1", LetterMeta.completeRecipient("Stadt X\nWeg 1", "", null))
    }

    @Test fun emptyLineAfterSalutation() {
        val out = LetterAnalysis.assemble("Betreff: X\nSehr geehrter Herr Y,\nich schreibe.\n\nMit freundlichen Grüßen", null, null, "16.11.2026", null)
        assertTrue(out, out.contains("Sehr geehrter Herr Y,\n\nich schreibe."))
    }
}

class LetterReviewTest {
    @Test fun bigAmountIsNoDate() {
        val out = LetterAnalysis.repairNumbers("Es geht um 1.234.567 Euro.", null, listOf("01.12.2026"), null)
        assertEquals("Es geht um 1.234.567 Euro.", out)
    }

    @Test fun ownAddressAloneIsNoRecipient() {
        val text = LetterAnalysis.assemble(
            "Betreff: X\n\nSehr geehrte Damen und Herren,\n\nText.\n\nMit freundlichen Grüßen",
            sender = "Paula Fischer\nLindenallee 18\n14612 Falkensee", recipient = null,
            placeDate = "Falkensee, 16.11.2026", name = "Paula Fischer",
        )
        val l = LetterLayout.parse(text)!!
        assertTrue(l.sender.startsWith("Paula Fischer"))
        assertEquals("", l.recipient)
    }
}
