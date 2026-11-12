package com.example.dexcode.ssh

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit

class ServerManagementTest {
    @Test fun versionsAndOperationFieldsCannotInjectShellCommands() {
        assertEquals("4.136.2",ServerManagement.version(" v4.136.2 "))
        for(value in listOf("latest","4.19","4.19.1;touch /tmp/x","4.19.1\nwhoami","1234567.0.0"))assertNull(ServerManagement.version(value))
        assertThrows(IllegalArgumentException::class.java){ServerManagement.header("install",8080)}
        assertThrows(IllegalArgumentException::class.java){ServerManagement.header("inspect",0)}
        assertThrows(IllegalArgumentException::class.java){ServerManagement.header("inspect",8080,mapOf("PATH" to "x"))}
        val value="/home/user's files/\$(false); launcher"
        val script=ServerManagement.header("inspect",8080,mapOf("EXPECTED_LAUNCHER" to value))+"printf '%s' \"\$EXPECTED_LAUNCHER\""
        val process=ProcessBuilder("sh","-c",script).start()
        assertEquals(value,process.inputStream.bufferedReader().readText())
        assertEquals(0,process.waitFor())
    }
    @Test fun protocolHandlesUnicodeAndRejectsAmbiguousFields() {
        val encoded=Base64.getEncoder().encodeToString("日本語\n/path with space".toByteArray())
        assertEquals("日本語\n/path with space",ServerManagement.parse("noise\nDEXCODE_SERVER\tlauncher\t$encoded\n")["launcher"])
        assertThrows(IllegalArgumentException::class.java){ServerManagement.parse("DEXCODE_SERVER\tx\tYQ==\nDEXCODE_SERVER\tx\tYg==")}
        assertThrows(IllegalArgumentException::class.java){ServerManagement.parse("DEXCODE_SERVER\tx\t%")}
    }
    @Test fun unsupportedOrUnverifiedInstallationsStayReadOnly() {
        val fields=mapOf("method" to "standalone","launcher" to "/home/u/.local/bin/code-server","realPath" to "/home/u/.local/lib/code-server-4.19.1/bin/code-server","targetVerified" to "1","toolsReady" to "1")
        assertTrue(ServerManagement.Snapshot(fields).canUpdate)
        assertFalse(ServerManagement.Snapshot(fields+ ("targetVerified" to "0")).canUpdate)
        assertFalse(ServerManagement.Snapshot(fields+ ("method" to "unknown")).canUpdate)
        assertFalse(ServerManagement.Snapshot(fields+ ("restartRequired" to "1")).canUpdate)
        assertFalse(ServerManagement.Snapshot(fields+ ("toolsReady" to "0")).canUpdate)
        val identity="12345678-1234-1234-1234-123456789abc:12345"
        assertTrue(ServerManagement.Snapshot(fields+mapOf("canRestart" to "1","pid" to "123","processIdentity" to identity)).canRestart)
        assertFalse(ServerManagement.Snapshot(fields+mapOf("canRestart" to "1","pid" to "1","processIdentity" to identity)).canRestart)
        assertFalse(ServerManagement.Snapshot(fields+mapOf("canRestart" to "1","pid" to "123")).canRestart)
    }
    @Test fun releaseMetadataRequiresExactTagAssetAndDigest() {
        val name=ServerManagement.assetName("standalone","4.136.2","amd64")
        val digest="a".repeat(64)
        val json=JSONObject().put("tag_name","v4.136.2").put("assets",org.json.JSONArray().put(JSONObject().put("name",name).put("digest","sha256:$digest")))
        assertEquals(digest,ServerManagement.releaseAsset(json,"4.136.2",name).sha256)
        assertThrows(IllegalArgumentException::class.java){ServerManagement.releaseAsset(json,"4.19.1",name)}
        json.getJSONArray("assets").getJSONObject(0).remove("digest")
        assertThrows(IllegalArgumentException::class.java){ServerManagement.releaseAsset(json,"4.136.2",name)}
        assertEquals("code-server_4.136.2_arm64.deb",ServerManagement.assetName("deb","4.136.2","arm64"))
    }
    @Test fun generatedDetachedLauncherExecutesLiteralPayloadAndPersistsResult() {
        val root=Files.createTempDirectory("dexcode-launcher's-test-").toFile()
        try {
            val id="12345678-1234-1234-1234-123456789abc"
            val runner=java.io.File("src/main/assets/server-job-runner.sh").readText()
            val script=ServerManagement.jobLauncher(id,8080,"printf 'literal payload\\n'\n",runner).replace("\$HOME","\${DEXCODE_TEST_ROOT}")
            val process=ProcessBuilder("sh","-c",script).apply {environment()["DEXCODE_TEST_ROOT"]=root.absolutePath}.start()
            assertTrue(process.waitFor(5,TimeUnit.SECONDS))
            assertEquals(0,process.exitValue())
            assertEquals(id,ServerManagement.parse(process.inputStream.bufferedReader().readText())["jobId"])
            val dir=java.io.File(root,".cache/dexcode/server-jobs/$id")
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(java.io.File(dir,"state").readText().trim() !in setOf("completed","failed") && System.nanoTime()<deadline)Thread.sleep(20)
            assertEquals("completed",java.io.File(dir,"state").readText().trim())
            assertEquals("literal payload\n",java.io.File(dir,"output").readText())
            assertEquals(id,java.io.File(root,".cache/dexcode/code-server-8080.last-job").readText().trim())
        } finally {root.deleteRecursively()}
    }
}
