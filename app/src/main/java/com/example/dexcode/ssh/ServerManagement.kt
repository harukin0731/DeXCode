package com.example.dexcode.ssh

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.io.ByteArrayOutputStream

object ServerManagement {
    private val versionPattern = Regex("[0-9]{1,6}\\.[0-9]{1,6}\\.[0-9]{1,6}")
    fun version(raw: String): String? = raw.trim().removePrefix("v").takeIf { versionPattern.matches(it) }
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    fun header(operation: String, port: Int, fields: Map<String, String> = emptyMap()): String {
        require(operation in setOf("inspect", "update", "restart", "job"))
        require(port in 1..65535)
        require(fields.keys.all { it in setOf("VERSION", "EXPECTED_LAUNCHER", "EXPECTED_REAL", "EXPECTED_METHOD", "EXPECTED_VERSION",
            "ALLOW_SUDO", "EXPECTED_PID", "EXPECTED_PROCESS_ID", "EXPECTED_ASSET", "EXPECTED_SHA256", "JOB_ID") })
        return "OP=${quote(operation)}\nPORT=$port\n" + fields.entries.joinToString("\n", postfix = "\n") { (key,value) -> "$key=${quote(value)}" }
    }
    fun parse(output: String): Map<String,String> = buildMap {
        output.lineSequence().filter { it.startsWith("DEXCODE_SERVER\t") }.forEach { line ->
            val fields = line.split('\t', limit=3)
            require(fields.size == 3 && fields[1].matches(Regex("[A-Za-z][A-Za-z0-9]*"))) { "Invalid server response" }
            require(!containsKey(fields[1])) { "Duplicate server response field" }
            put(fields[1], String(Base64.getDecoder().decode(fields[2]), Charsets.UTF_8))
        }
    }
    fun jobLauncher(job:String,port:Int,payload:String,runner:String):String {
        require(job.matches(Regex("[a-f0-9-]{36}")) && port in 1..65535)
        val delimiter="DEXCODE_JOB_"+job.replace("-","")
        require(payload.lineSequence().none{it==delimiter} && runner.lineSequence().none{it==delimiter})
        val dir="\"\$HOME/.cache/dexcode/server-jobs/$job\""
        return listOf(
            "set -eu", "umask 077", "JOB_DIR=$dir", "mkdir -p \"\$JOB_DIR\"",
            "cat > \"\$JOB_DIR/payload.sh\" <<'$delimiter'", payload, delimiter,
            "cat > \"\$JOB_DIR/runner.sh\" <<'$delimiter'", "JOB_DIR=$dir", runner, delimiter,
            "date +%s > \"\$JOB_DIR/created\"", "printf 'queued\\n' > \"\$JOB_DIR/state\"",
            "printf '%s\\n' '$job' > \"\$HOME/.cache/dexcode/code-server-$port.last-job.$job.tmp\"",
            "mv -f \"\$HOME/.cache/dexcode/code-server-$port.last-job.$job.tmp\" \"\$HOME/.cache/dexcode/code-server-$port.last-job\"",
            "if command -v setsid >/dev/null 2>&1; then",
            "  setsid nohup sh \"\$JOB_DIR/runner.sh\" </dev/null >/dev/null 2>&1 &",
            "else", "  nohup sh \"\$JOB_DIR/runner.sh\" </dev/null >/dev/null 2>&1 &", "fi",
            "printf 'DEXCODE_SERVER\\tjobId\\t'", "printf '%s' '$job' | base64 | tr -d '\\n'", "printf '\\n'", ""
        ).joinToString("\n")
    }


    data class Snapshot(val fields: Map<String,String>) {
        val launcher get() = fields["launcher"].orEmpty()
        val realPath get() = fields["realPath"].orEmpty()
        val installedVersion get() = fields["version"].orEmpty()
        val runningVersion get() = fields["runningVersion"].orEmpty()
        val method get() = fields["method"].orEmpty()
        val needsAdmin get() = fields["needsAdmin"] == "1"
        val restartRequired get() = fields["restartRequired"] == "1"
        val canUpdate get() = !restartRequired && fields["targetVerified"] == "1" && fields["toolsReady"] == "1" &&
            method in setOf("standalone","deb","rpm","npm") && launcher.startsWith("/") && realPath.startsWith("/")
        val canRestart get() = fields["canRestart"] == "1" && (fields["pid"]?.toIntOrNull() ?: 0) > 1 &&
            fields["processIdentity"].orEmpty().matches(Regex("[a-fA-F0-9-]{36}:[0-9]+"))
        val previousVersion get() = version(fields["previousVersion"].orEmpty())
        val localVersions get() = fields["availableVersions"].orEmpty().split(Regex("\\s+")).mapNotNull(::version).toSet()
        val lastJob get() = fields["lastJob"]?.takeIf { it.matches(Regex("[a-f0-9-]{36}")) }
        fun expectation() = mapOf("EXPECTED_LAUNCHER" to launcher,"EXPECTED_REAL" to realPath,
            "EXPECTED_METHOD" to method,"EXPECTED_VERSION" to installedVersion)
    }
    data class Asset(val name:String,val sha256:String)
    fun assetName(method:String, version:String, arch:String):String {
        require(this.version(version)==version && arch in setOf("amd64","arm64"))
        return when(method) {
            "standalone" -> "code-server-$version-linux-$arch.tar.gz"
            "deb" -> "code-server_${version}_$arch.deb"
            "rpm" -> "code-server-$version-$arch.rpm"
            else -> error("No release asset for this installation")
        }
    }
    fun releaseAsset(json:JSONObject, version:String, name:String):Asset {
        require(json.getString("tag_name")=="v$version") { "Unexpected release tag" }
        val assets=json.getJSONArray("assets")
        for(i in 0 until assets.length()) {
            val asset=assets.getJSONObject(i)
            if(asset.getString("name")==name) {
                val digest=asset.optString("digest").removePrefix("sha256:")
                require(digest.matches(Regex("[a-f0-9]{64}"))) { "This release does not publish a SHA-256 digest. Select an already installed standalone version or use the host's package manager." }
                return Asset(name,digest)
            }
        }
        error("No release asset matches this host")
    }
    fun latestVersion():String = version(request("latest").getString("tag_name")) ?: error("Invalid release version")
    fun asset(method:String, version:String, arch:String):Asset = releaseAsset(request("tags/v$version"),version,assetName(method,version,arch))
    private fun request(suffix:String):JSONObject {
        val conn=URL("https://api.github.com/repos/coder/code-server/releases/$suffix").openConnection() as HttpURLConnection
        conn.connectTimeout=15_000;conn.readTimeout=15_000
        conn.setRequestProperty("Accept","application/vnd.github+json")
        conn.setRequestProperty("User-Agent","DeXCode")
        try {
            check(conn.responseCode==200) { "GitHub release lookup failed (HTTP ${conn.responseCode})" }
            val bytes=ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buffer=ByteArray(8192)
                while(true){val count=input.read(buffer);if(count<0)break;check(bytes.size()+count<=524288){"Release response too large"};bytes.write(buffer,0,count)}
            }
            return JSONObject(bytes.toString("UTF-8"))
        } finally {conn.disconnect()}
    }
}
