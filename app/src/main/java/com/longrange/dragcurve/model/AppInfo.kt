package com.longrange.dragcurve.model

object AppInfo {
    const val SOURCE_URL = "https://github.com/longrangelab/skyward-ballistics"
    const val PRIVACY_URL = "$SOURCE_URL/blob/main/PRIVACY.md"
    const val LICENSE_URL = "$SOURCE_URL/blob/main/LICENSE"
    const val LICENSE_NAME = "GNU General Public License v3.0"

    const val DISCLAIMER_TITLE = "Before you shoot"
    const val DISCLAIMER =
        "Skyward Ballistics gives predictions, not guarantees. Its answers are only as good as the numbers you enter, " +
            "and real bullets, rifles and weather never match a model exactly.\n\n" +
            "Always confirm your data with live fire at known distances before you rely on it, " +
            "and always follow safe firearm handling and the rules of your range and your local laws.\n\n" +
            "You use this app at your own risk. It comes with no warranty, and its authors are not responsible " +
            "for any miss, damage or injury."

    const val LICENSE_NOTE =
        "Skyward Ballistics is free, open-source software under the $LICENSE_NAME. " +
            "You can read, copy and change its source code under that license."
}
