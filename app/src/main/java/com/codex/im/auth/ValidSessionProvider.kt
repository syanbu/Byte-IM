package com.codex.im.auth

typealias ValidSessionProvider = suspend () -> AuthSession?
