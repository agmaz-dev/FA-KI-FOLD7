package de.foldki.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LetterMetaTest {

    private val jobcenter = """
        Jobcenter Musterstadt · Hauptstraße 5 · 12345 Musterstadt

        Herrn
        Paula Fischer
        Lindenweg 3
        10115 Berlin

                                                   Datum: 12.11.2026
        Aktenzeichen: 123-BG-4567

        Betreff: Aufhebungsbescheid
        Sehr geehrte Frau Fischer,
        bitte reichen Sie die Unterlagen bis zum 30.11.2026 ein.
        Ihr Geburtsdatum: 01.02.1980
    """.trimIndent()

    @Test fun numericDateWithLabel() {
        val m = LetterMeta.extract(jobcenter, listOf("Paula Fischer"))
        assertEquals("12.11.2026", m.date)
        assertEquals("Jobcenter Musterstadt\nHauptstraße 5\n12345 Musterstadt", m.recipient)
        assertEquals("Aktenzeichen: 123-BG-4567", m.refNo)
        assertEquals("Aufhebungsbescheid", m.subject)
    }

    @Test fun writtenMonthAndPlaceDen() {
        assertEquals("12.11.2026", LetterMeta.findDate("Muster GmbH\nBerlin, den 12. November 2026\nSehr geehrte Damen und Herren,"))
        assertEquals("03.03.2026", LetterMeta.findDate("Hamburg, 3. März 2026"))
        assertEquals("05.01.2026", LetterMeta.findDate("Stand 2026-01-05"))
        assertEquals("07.10.2026", LetterMeta.findDate("München, 7.10.26"))
    }

    @Test fun letterheadDateBeatsOtherDates() {
        val t = """
            Stadtwerke Muster GmbH
            Am Markt 1
            54321 Muster
                                                   Muster, 02.10.2026
            Rechnung für den Zeitraum 01.09.2026 bis 30.09.2026
            Zahlbar bis 20.10.2026
        """.trimIndent()
        assertEquals("02.10.2026", LetterMeta.findDate(t))
    }

    @Test fun deadlinesBirthdaysAndReferencesAreIgnored() {
        assertNull(LetterMeta.findDate("Bitte zahlen Sie bis zum 30.11.2026.\nFrist: 15.12.2026\ngeboren am 01.02.1980"))
        assertNull(LetterMeta.findDate("Betreff: Ihr Schreiben vom 01.10.2026"))
        assertNull(LetterMeta.findDate("Betreff: Bescheid vom 01.10.2026"))
    }

    @Test fun noHit() {
        val m = LetterMeta.extract("Hallo, wie geht es dir?\nBis bald")
        assertNull(m.date); assertNull(m.recipient); assertNull(m.refNo); assertNull(m.subject)
    }

    @Test fun recipientFromLetterheadBlockNotTheUser() {
        val t = """
            Finanzamt Musterstadt
            Steuerstraße 10
            12345 Musterstadt

            Frau
            Erika Mustermann
            Rosenweg 2
            10115 Berlin

            Steuernummer: 12/345/67890
            Musterstadt, 05.11.2026
        """.trimIndent()
        val m = LetterMeta.extract(t)
        assertEquals("Finanzamt Musterstadt\nSteuerstraße 10\n12345 Musterstadt", m.recipient)
        assertEquals("05.11.2026", m.date)
        assertEquals("Steuernummer: 12/345/67890", m.refNo)
    }

    @Test fun ownAddressIsNeverTheRecipient() {
        val t = "Paula Fischer\nLindenweg 3\n10115 Berlin\n\nAOK Nordost\nPostfach 1234\n14400 Potsdam"
        val m = LetterMeta.extract(t, listOf("Paula Fischer"))
        assertEquals("AOK Nordost\nPostfach 1234\n14400 Potsdam", m.recipient)
    }

    @Test fun commaSenderLineAndNameOnly() {
        val m = LetterMeta.extract("Hausverwaltung Schmidt, Bahnhofstr. 7, 20095 Hamburg\nHerrn Max Muster")
        assertEquals("Hausverwaltung Schmidt\nBahnhofstr. 7\n20095 Hamburg", m.recipient)
        val n = LetterMeta.extract("Deutsche Rentenversicherung Bund\nSehr geehrte Frau Muster,")
        assertTrue(n.recipient!!.startsWith("Deutsche Rentenversicherung"))
    }
}
