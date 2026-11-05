package no.nav.sosialhjelp.upload.common

/** A submit for this submission is already running (or Fiks has already started receiving it). Maps to HTTP 409. */
class SubmitInProgressException(
    message: String,
) : RuntimeException(message)
