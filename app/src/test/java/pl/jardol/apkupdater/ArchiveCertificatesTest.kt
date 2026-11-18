package pl.jardol.apkupdater

import android.app.Application
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.ClassName
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Suppress("DEPRECATION")
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 29, 35], shadows = [
    ArchiveCertificatesTest.FrameworkSigningInfo::class, ArchiveCertificatesTest.JvmFileDataSource::class,
])
class ArchiveCertificatesTest {
    // Let the framework populate/read actual SigningInfo, rather than Robolectric's separate mock state.
    @Implements(SigningInfo::class)
    class FrameworkSigningInfo

    // Android's mmap DirectByteBuffer constructor is not accessible on desktop JDKs.
    // Replace file IO only; the framework still verifies the APK's cryptographic signature.
    @Implements(className = "android.util.apk.MemoryMappedFileDataSource", isInAndroidSdk = false)
    class JvmFileDataSource {
        @RealObject lateinit var source: Any

        @Implementation
        fun feedIntoDataDigester(@ClassName("android.util.apk.DataDigester") digester: Any, offset: Long, size: Int) {
            val fd = ReflectionHelpers.getField<FileDescriptor>(source, "mFd")
            val position = ReflectionHelpers.getField<Long>(source, "mFilePosition")
            val buffer = ByteBuffer.allocate(size)
            val channel = FileInputStream(fd).channel
            while (buffer.hasRemaining()) {
                check(channel.read(buffer, position + offset + buffer.position()) > 0) { "Unexpected APK EOF" }
            }
            buffer.flip()
            val consume = digester.javaClass.getDeclaredMethod("consume", ByteBuffer::class.java)
            consume.isAccessible = true
            consume.invoke(digester, buffer)
        }
    }
    private val ctx get() = RuntimeEnvironment.getApplication()
    // Empty APK generated with aapt and signed with this repository's Updater key, not E2Stream's key.
    private val fixtureCertificate = "460ea90a471226d4cad44b051bc764110b48fdc3274613bd38c8fcfecb8177fa"
    private val request = InstallRequest("e2stream-1.0", AppItem.STABLE, false)

    @Test
    fun realArchiveParserCollectsSignerWithArchiveFlags() {
        val apk = fixture()
        val info = requireNotNull(InstallRequest.readArchive(ctx, apk.absolutePath))
        assertEquals(InstallRequest.PACKAGE, info.packageName)
        assertEquals("1.0", info.versionName)
        assertNotNull(info.signingInfo)
        assertEquals(listOf(fixtureCertificate), InstallRequest.certificates(info))
        assertEquals(PackageManager.GET_SIGNING_CERTIFICATES, InstallRequest.signatureFlags())
        val expectedFlags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        assertEquals(expectedFlags, InstallRequest.archiveSignatureFlags())
        assertThrows(IllegalArgumentException::class.java) {
            request.validateApk(info.packageName, info.versionName, "1.0", InstallRequest.certificates(info))
        }
    }

    @Test
    @Config(sdk = [28])
    fun androidNineModernFlagAloneDoesNotCollectArchiveCertificates() {
        val apk = fixture()
        val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        assertNotNull(info)
        assertTrue(InstallRequest.certificates(requireNotNull(info)).isEmpty())
        assertEquals(listOf(fixtureCertificate),
            InstallRequest.certificates(requireNotNull(InstallRequest.readArchive(ctx, apk.absolutePath))))
    }

    @Test
    fun missingArchiveCertificatesCannotPassThePinCheck() {
        val info = PackageInfo().apply {
            packageName = InstallRequest.PACKAGE
            versionName = "1.0"
        }
        assertTrue(InstallRequest.certificates(info).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            request.validateApk(info.packageName, info.versionName, "1.0", InstallRequest.certificates(info))
        }
    }

    @Test
    fun unsignedArchiveDoesNotGainCertificatesFromItsManifest() {
        val signed = fixture()
        val unsigned = File(ctx.cacheDir, "unsigned.apk")
        ZipFile(signed).use { source ->
            ZipOutputStream(unsigned.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("AndroidManifest.xml"))
                source.getInputStream(source.getEntry("AndroidManifest.xml")).use { it.copyTo(output) }
                output.closeEntry()
            }
        }
        val info = InstallRequest.readArchive(ctx, unsigned.absolutePath)
        val certificates = info?.let { InstallRequest.certificates(it) } ?: emptyList()
        assertTrue(certificates.isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            request.validateApk(InstallRequest.PACKAGE, "1.0", "1.0", certificates)
        }
    }

    @Test
    fun archiveParserRejectsTamperedApkRatherThanTrustingItsEmbeddedCertificate() {
        val apk = fixture()
        RandomAccessFile(apk, "rw").use {
            // Change a ZIP timestamp: the manifest remains readable, but the APK digest no longer matches.
            it.seek(10)
            val original = it.read()
            it.seek(10)
            it.write(original xor 1)
        }
        assertNull(InstallRequest.readArchive(ctx, apk.absolutePath))
    }

    @Test
    @Config(shadows = [org.robolectric.shadows.ShadowSigningInfo::class])
    fun modernSignerDataTakesPrecedenceOverLegacySignatures() {
        val modern = Signature(byteArrayOf(1, 2, 3))
        val legacy = Signature(byteArrayOf(4, 5, 6))
        val info = PackageInfo().apply {
            signatures = arrayOf(legacy)
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(modern)) }
        }
        val expected = MessageDigest.getInstance("SHA-256").digest(modern.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(listOf(expected), InstallRequest.certificates(info))
        shadowOf(info.signingInfo).setSignatures(emptyArray())
        assertTrue(InstallRequest.certificates(info).isEmpty())
    }

    @Test
    fun legacyCertificatesAloneNeverSubstituteForCurrentSigningInfo() {
        val info = PackageInfo().apply { signatures = arrayOf(Signature(byteArrayOf(1, 2, 3))) }
        assertTrue(InstallRequest.certificates(info).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            request.validateApk(InstallRequest.PACKAGE, "1.0", "1.0", InstallRequest.certificates(info))
        }
    }

    private fun fixture(): File = File(ctx.cacheDir, "signed-fixture.apk").also { file ->
        requireNotNull(javaClass.getResourceAsStream("/archive-signer-fixture.apk")).use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }
}
