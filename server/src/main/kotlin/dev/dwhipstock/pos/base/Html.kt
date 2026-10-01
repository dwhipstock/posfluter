package dev.dwhipstock.pos.base

/** Text or a double/single-quoted attribute value: & < > " ' all escaped (red-team: a quote broke out of alt="…"). */
fun String.escapeHtml(): String =
    replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")
