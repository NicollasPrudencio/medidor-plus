package com.consumo.tuya

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/**
 * Bandeira tarifária. [defaultAdd] = adicional em R$/kWh (sem impostos) — valores da ANEEL
 * usados como padrão (conferido na conta CEEE de set/2026: amarela ≈ 0,01886); podem ser
 * ajustados nas configurações se a ANEEL mudar.
 */
enum class Flag(val code: String, val label: String, val defaultAdd: Double) {
    VERDE("verde", "Verde", 0.0),
    AMARELA("amarela", "Amarela", 0.01885),
    VERMELHA1("vermelha1", "Vermelha P1", 0.04463),
    VERMELHA2("vermelha2", "Vermelha P2", 0.07877);

    companion object {
        fun of(code: String?): Flag? = entries.firstOrNull { it.code == code }
    }
}

/** Desconto (%) a partir do preço cheio e do valor cobrado (ex.: 524,07 e 461,18 → 12%). */
fun discountFrom(full: Double, charged: Double): Double =
    if (full > 0 && charged > 0 && charged < full) (1 - charged / full) * 100 else 0.0

/** "AAAA-MM" do instante (fuso do aparelho). */
fun monthKey(ms: Long): String = YearMonth.from(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())).toString()

/** Composição do custo. [discount] é negativo (abatimento). */
data class CostBreakdown(val energy: Double, val flag: Double, val taxes: Double, val discount: Double = 0.0) {
    val total get() = energy + flag + taxes + discount
    operator fun plus(o: CostBreakdown) =
        CostBreakdown(energy + o.energy, flag + o.flag, taxes + o.taxes, discount + o.discount)

    companion object {
        val ZERO = CostBreakdown(0.0, 0.0, 0.0, 0.0)
    }
}

/**
 * Como transformar kWh em R$.
 * - Simples: kWh × tarifa única (com impostos).
 * - Detalhado (mesma conta da distribuidora, ex.: CEEE):
 *   preço = (tarifa base TE+TUSD + adicional da bandeira) ÷ ((1 − ICMS) × (1 − PIS − COFINS)) × (1 − desconto)
 *   PIS/COFINS não incidem sobre o ICMS, por isso os divisores são multiplicados.
 *   O desconto cobre geração compartilhada / energia por assinatura (ex.: FIT Energia).
 *   Não inclui itens fixos da conta (iluminação pública, taxa de disponibilidade).
 */
data class CostModel(
    val detailed: Boolean,
    val simpleTariff: Double,
    val base: Double = 0.0,
    val icmsPct: Double = 0.0,
    val pisCofinsPct: Double = 0.0,
    val discountPct: Double = 0.0,
    val flagAdd: Map<Flag, Double> = Flag.entries.associateWith { it.defaultAdd },
    val flags: Map<String, Flag> = emptyMap(),
) {
    private val taxDivisor
        get() = ((1 - icmsPct / 100) * (1 - pisCofinsPct / 100)).coerceAtLeast(0.01)
    private val discountFactor get() = (1 - discountPct / 100).coerceIn(0.0, 1.0)

    /** Bandeira do mês do instante (sem cadastro = verde). */
    fun flagAt(ms: Long): Flag = flags[monthKey(ms)] ?: Flag.VERDE

    fun pricePerKwh(ms: Long): Double =
        if (!detailed) simpleTariff
        else (base + (flagAdd[flagAt(ms)] ?: 0.0)) / taxDivisor * discountFactor

    fun cost(kwh: Double?, ms: Long): Double? = kwh?.times(pricePerKwh(ms))

    fun breakdown(kwh: Double, ms: Long): CostBreakdown {
        if (!detailed) return CostBreakdown(kwh * simpleTariff, 0.0, 0.0)
        val energy = kwh * base
        val flag = kwh * (flagAdd[flagAt(ms)] ?: 0.0)
        val gross = (energy + flag) / taxDivisor
        return CostBreakdown(energy, flag, gross - energy - flag, -gross * (1 - discountFactor))
    }

    companion object {
        fun simple(tariff: Double) = CostModel(detailed = false, simpleTariff = tariff)
    }
}
