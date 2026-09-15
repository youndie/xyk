package io.github.youndie.xyk

/**
 * Names the outbound HTTP engine this binary was linked with, and — on the native builds — makes
 * sure it was actually linked.
 *
 * **It exists to be reachable.** B-05 asks what the curl engine costs a static link, and a
 * dependency nothing calls is a dependency the linker removes: `--gc-sections` would drop every byte
 * of libcurl and the measurement would compare a binary with curl in its POM against one without.
 * So `main` calls this on every start, and the `with-curl` variant constructs a real client here.
 *
 * The variant is chosen by `-Pxyk.httpClient=true|false` at build time, not at run time. Once B-10
 * gives delivery a real client this function collapses into it; until then it is the smallest thing
 * that can honestly answer the question.
 */
expect fun httpEngineMarker(): String
