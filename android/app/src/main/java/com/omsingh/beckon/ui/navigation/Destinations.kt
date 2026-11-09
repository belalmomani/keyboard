package com.omsingh.beckon.ui.navigation

import kotlinx.serialization.Serializable

/** Where the app can go. These are the type-safe routes the navigation graph uses. */
@Serializable data object Onboarding

@Serializable data object Devices

/** The camera, scanning the QR code on a PC's screen. Not a tab: it covers the whole screen. */
@Serializable data object Scan

/** Linking this phone with another one: a code to show, or to scan. */
@Serializable data object PhoneLink

/** Controlling a TV over Wi-Fi. */
@Serializable data object TvRemote

/** The camera, scanning the code on another phone's screen. */
@Serializable data object ScanLink

@Serializable data object Remote

@Serializable data object Settings

/** The list of open source libraries and their licenses, opened from the About page. */
@Serializable data object Licenses

/** One page of the settings, by the name of its [com.omsingh.beckon.ui.screens.settings.SettingsPage]. */
@Serializable data class SettingsDetail(val page: String)
