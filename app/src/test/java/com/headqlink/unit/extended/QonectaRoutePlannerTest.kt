package com.headqlink.unit.extended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QonectaRoutePlannerTest {

    @Test
    fun classifyNetwork_recognizesSpanishAndEuropeanOperators() {
        assertEquals("iberdrola", QonectaRoutePlanner.classifyNetwork("Iberdrola Clientes", "", "", ""))
        assertEquals("zunder", QonectaRoutePlanner.classifyNetwork("", "", "Zunder Palencia", ""))
        assertEquals("ionity", QonectaRoutePlanner.classifyNetwork("", "IONITY", "", ""))
        assertEquals("endesa", QonectaRoutePlanner.classifyNetwork("", "", "", "Endesa X Way"))
        assertEquals("tesla", QonectaRoutePlanner.classifyNetwork("Tesla Supercharger", "", "", ""))
        assertEquals("other", QonectaRoutePlanner.classifyNetwork("", "", "Cargador Municipal", ""))

        assertEquals("Iberdrola", QonectaRoutePlanner.networkLabel("iberdrola"))
        val zunderBadge = QonectaRoutePlanner.networkBadge("zunder")
        assertNotNull(zunderBadge)
        assertEquals("Z", zunderBadge?.initials)
        assertTrue((QonectaRoutePlanner.networkBadge("ionity")?.colorArgb ?: 0) != 0)
    }

    @Test
    fun voltsClassification_detects800vAnd400vLimitationOn800vCar() {
        val cIonity = QonectaRoutePlanner.Charger(
            id = "io1",
            name = "IONITY Benavente",
            detail = "A-6",
            lat = 42.0,
            lon = -5.67,
            kmAlong = 240.0,
            distFromRouteKm = 0.4,
            maxKw = 350.0,
            network = "ionity",
            maxVolts = 800.0,
        )
        val cTesla = QonectaRoutePlanner.Charger(
            id = "ts1",
            name = "Tesla Supercharger Tordesillas",
            detail = "A-6",
            lat = 41.5,
            lon = -5.0,
            kmAlong = 180.0,
            distFromRouteKm = 0.5,
            maxKw = 150.0,
            network = "tesla",
            maxVolts = 500.0,
        )
        assertTrue(QonectaRoutePlanner.highVolts(cIonity))
        assertFalse(QonectaRoutePlanner.lowVolts(cIonity, 800.0))
        assertTrue(QonectaRoutePlanner.lowVolts(cTesla, 800.0))
        assertFalse(QonectaRoutePlanner.lowVolts(cTesla, 400.0))
    }

    private fun makeTestPlan(totalKm: Double, totalKwh: Double, chargers: List<QonectaRoutePlanner.Charger>): QonectaRoutePlanner.Plan {
        val n = 41
        val km = DoubleArray(n) { i -> (totalKm * i) / (n - 1) }
        val kwhCum = DoubleArray(n) { i -> (totalKwh * i) / (n - 1) }
        val zeros = DoubleArray(n)
        return QonectaRoutePlanner.Plan(
            originName = "Madrid",
            originIsRealGps = true,
            originLat = 40.4168,
            originLon = -3.7038,
            destination = "A Coruña",
            destDetail = "Galicia",
            destLat = 43.3623,
            destLon = -8.4115,
            n = n,
            lat = DoubleArray(n) { 40.4168 },
            lon = DoubleArray(n) { -3.7038 },
            km = km,
            elev = DoubleArray(n) { 600.0 },
            headwind = zeros,
            temp = DoubleArray(n) { 18.0 },
            segS = DoubleArray(n - 1) { 60.0 },
            kwhCum = kwhCum,
            gravCum = zeros,
            totalKm = totalKm,
            totalSeconds = 14400L,
            flatElevation = false,
            destTemp = 18.0,
            destWind = 10.0,
            destRain = 0.0,
            destCode = 0,
            chargers = chargers,
            dgtCount = chargers.size,
        )
    }

    @Test
    fun planChargingStops_noStopsNeededOnShortTrip() {
        val plan = makeTestPlan(totalKm = 75.0, totalKwh = 12.0, chargers = emptyList())
        val settings = QonectaRoutePlanner.ChargeSettings(
            arriveMinPct = 15.0,
            stopMinPct = 10.0,
            maxChargePct = 80.0,
            carPeakKw = 84.0,
            capacityKwh = 69.9,
            carVolts = 400.0,
        )
        val res = QonectaRoutePlanner.planChargingStops(plan, socNow = 80.0, filteredChargers = emptyList(), settings = settings)
        assertEquals(QonectaRoutePlanner.Outcome.NO_STOPS, res.outcome)
        assertTrue(res.stops.isEmpty())
        assertTrue(res.arrivalPct > 55.0)
    }

    @Test
    fun planChargingStops_plansStopAndFindsAlternativesOnLongTrip() {
        val chargers = listOf(
            QonectaRoutePlanner.Charger(
                id = "zu1",
                name = "Zunder Tordesillas",
                detail = "A-6 km 180",
                lat = 41.5000,
                lon = -5.0000,
                kmAlong = 180.0,
                distFromRouteKm = 0.4,
                maxKw = 180.0,
                network = "zunder",
                maxVolts = 800.0,
            ),
            QonectaRoutePlanner.Charger(
                id = "ib1",
                name = "Iberdrola Tordesillas",
                detail = "A-6 km 182",
                lat = 41.5050,
                lon = -5.0020,
                kmAlong = 182.0,
                distFromRouteKm = 0.6,
                maxKw = 150.0,
                network = "iberdrola",
                maxVolts = 400.0,
            ),
            QonectaRoutePlanner.Charger(
                id = "io1",
                name = "Ionity Benavente",
                detail = "A-6 km 245",
                lat = 42.0000,
                lon = -5.6700,
                kmAlong = 245.0,
                distFromRouteKm = 0.5,
                maxKw = 350.0,
                network = "ionity",
                maxVolts = 800.0,
            ),
        )
        val plan = makeTestPlan(totalKm = 420.0, totalKwh = 78.0, chargers = chargers)
        val settings = QonectaRoutePlanner.ChargeSettings(
            arriveMinPct = 15.0,
            stopMinPct = 10.0,
            maxChargePct = 80.0,
            carPeakKw = 84.0,
            capacityKwh = 69.9,
            carVolts = 400.0,
            fastChargePriceEur = 0.45,
        )
        val res = QonectaRoutePlanner.planChargingStops(plan, socNow = 75.0, filteredChargers = chargers, settings = settings)
        assertEquals(QonectaRoutePlanner.Outcome.PLANNED, res.outcome)
        assertEquals(1, res.stops.size)
        val stop = res.stops[0]
        assertTrue(stop.arrivePct >= 10.0)
        assertTrue(stop.departPct > stop.arrivePct)
        assertTrue(stop.minutes >= 5.0)
        assertTrue(res.totalCostEur > 0.0)
        assertTrue(res.arrivalPct >= 14.9)

        val alts = QonectaRoutePlanner.stopAlternatives(
            currentResult = res,
            stopIndex = 0,
            chargers = chargers,
            settings = settings,
        )
        assertTrue(alts.isNotEmpty())
    }
}
