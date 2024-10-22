package no.nav.sf.pubsub

fun readResourceFile(path: String) = Application::class.java.getResource(path).readText()
