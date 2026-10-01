package dev.dwhipstock.poscloud

/** Error envelope contract: non-2xx bodies are { "error", "code" } (API.md). */

class NotFoundException(message: String, val code: String = "not_found") : RuntimeException(message)

class ConflictException(message: String, val code: String) : RuntimeException(message)

class BadRequestException(message: String, val code: String = "bad_request") : RuntimeException(message)

class UnauthorizedException(message: String = "not authenticated", val code: String = "not_authenticated") :
    RuntimeException(message)

class RateLimitException(message: String = "too many attempts", val retryAfterSeconds: Long = 60) :
    RuntimeException(message)

/** Signed in, but this portal user's role may not do that (403). */
class ForbiddenException(message: String, val code: String = "forbidden") : RuntimeException(message)

class PayloadTooLargeException(message: String, val code: String = "payload_too_large") :
    RuntimeException(message)
