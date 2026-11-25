// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.fork

import com.android.inputmethod.latin.utils.BinaryDictionaryUtils
import helium314.keyboard.latin.makedict.DictionaryHeader
import helium314.keyboard.latin.makedict.UnsupportedFormatException
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

/**
 * The fork bundles emoji dictionaries, so starting LatinIME now opens one. Unit tests run without
 * the native library, so header reads report an unreadable file and the dictionary is skipped.
 */
@Implements(BinaryDictionaryUtils::class)
object ShadowNoNativeDictionaries {
    @Implementation
    @JvmStatic
    fun getHeaderWithOffsetAndLength(dictFile: File, offset: Long, length: Long): DictionaryHeader =
        throw UnsupportedFormatException("no native dictionary library in unit tests")
}
