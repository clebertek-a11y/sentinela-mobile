package br.gov.rs.sapucaiadosul.sentinela

/** Catalogo do formulario de BA usado pela Defesa Civil. */
object OccurrenceCatalog {
    val requesterTypes = listOf("Proprietário","Inquilino","Vizinho","Parente","Outros")
    val attendanceTypes = listOf("Emergencial","Preventiva")
    val categories = linkedMapOf(
        "Ameaça/Indício" to listOf(
            "Alagamento","Enchente","Inundação","Queda de árvore",
            "Desabamento Imóvel","Desabamento Marquise","Movimento de Massa",
            "Desprendimento de Reboco","Desabamento de Muro","Imóvel com Rachadura",
            "Danos em Residência - Evento Climático Adverso",
            "Danos em Veículo - Evento Climático Adverso"
        ),
        "Desabamento/Deslizamento" to listOf(
            "Desabamento de Imóvel","Desabamento de Muro","Desabamento de Marquise",
            "Desabamento de Reboco","Deslizamento de Encosta","Deslizamento de Barreira"
        ),
        "Incêndio" to listOf(
            "Incêndio em Área Rural","Incêndio em Parque/APP",
            "Incêndio Urbano Residencial","Incêndio Urbano Industrial"
        ),
        "Outros" to listOf(
            "Queda Vegetal Área Pública","Queda Vegetal Área Particular",
            "Poda de Vegetal","Supressão Vegetal","Alagamento","Enchente","Inundação",
            "Atendimento/Primeiros Socorros","Construção Irregular","Ocupação Irregular",
            "Captura de Abelha","Prevenção/Vistoria a Eventos","Infiltração",
            "Vegetal com Fiação Energizada","Apoio a Secretarias",
            "Apoio a Eventos Municipais"
        )
    )
    val flat = categories.flatMap { (group, values) -> values.map { "$group - $it" } }
}