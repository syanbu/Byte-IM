package com.buyansong.im.auth

typealias ValidSessionProvider = suspend () -> AuthSession?
