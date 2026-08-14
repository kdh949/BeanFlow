package io.github.kdh949.beanflow.shared.internal

import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/auth")
internal class CsrfTokenController {
    @GetMapping("/customer/csrf")
    fun customer(csrfToken: CsrfToken): ResponseEntity<Void> {
        csrfToken.token
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/merchant/csrf")
    fun merchant(csrfToken: CsrfToken): ResponseEntity<Void> {
        csrfToken.token
        return ResponseEntity.noContent().build()
    }
}
