"""Exercise the production repository declarations with real Gradle resolution.

Set ENDLESS_TEST_GRADLE to an installed Gradle executable. The CI harness job
installs the wrapper's version. HTTP is confined to a disposable loopback fixture;
the production repositories and their TLS settings are unchanged.
"""
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from urllib.parse import unquote, urlsplit
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GRADLE = os.environ.get('ENDLESS_TEST_GRADLE')


def repository_declarations():
    source = (ROOT/'build.gradle').read_text()
    start = source.index('repositories {', source.index('allprojects {'))
    depth = 0
    for index in range(source.index('{', start), len(source)):
        depth += (source[index] == '{') - (source[index] == '}')
        if depth == 0:
            return source[start:index+1]
    raise AssertionError('Unterminated production repositories block')


@unittest.skipUnless(GRADLE, 'Set ENDLESS_TEST_GRADLE to run the Gradle resolution regression')
class RepositoryRoutingTests(unittest.TestCase):
    def test_unrelated_dependencies_bypass_failed_snapshots_without_hiding_required_artifacts(self):
        coordinates = {
            'net.fabricmc:intermediary:1.21.1': 'fabric',
            'maven.modrinth:sodium:zg4YQ9EL': 'modrinth',
            'net.blay09.mods:balm-fabric:7.3.43+1.20.1': 'twelve',
            'net.blay09.mods:waystones-fabric:14.1.21+1.20.1': 'twelve',
            'remapped.net.fabricmc.fabric-api:fabric-api-fixture:1': 'loom',
        }
        files, hashes, requests = {}, {}, []
        for coordinate, repository in coordinates.items():
            group, artifact, version = coordinate.split(':')
            prefix = f'/{repository}/{group.replace(".","/")}/{artifact}/{version}/{artifact}-{version}'
            files[prefix+'.pom'] = (f'<project><modelVersion>4.0.0</modelVersion><groupId>{group}</groupId>'
                f'<artifactId>{artifact}</artifactId><version>{version}</version></project>').encode()
            archive = io.BytesIO()
            with zipfile.ZipFile(archive, 'w') as jar:
                jar.writestr('provenance.txt', repository+':'+coordinate)
            files[prefix+'.jar'] = archive.getvalue()
            hashes[coordinate] = hashlib.sha256(archive.getvalue()).hexdigest()

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                path = unquote(urlsplit(self.path).path)
                requests.append(path)
                status = 521 if path.startswith(('/snapshots/', '/mirror/')) else (200 if path in files else 404)
                body = files.get(path, b'Unavailable')
                self.send_response(status)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temporary:
                directory = Path(temporary)
                (directory/'settings.gradle').write_text("rootProject.name='repository-routing-regression'\n")
                # Evaluate the actual production DSL, not a copy of its filters.
                script = repository_declarations()+'''
repositories {
    maven { url = uri('https://maven.fabricmc.net/') }
    maven { url = uri('https://api.modrinth.com/maven') }
    maven { url = uri('https://fixture.invalid/loom') }
}
def base = @BASE@
repositories.all { repo ->
    def original = repo.url
    def route = original.host == 'maven.ithundxr.dev' ? original.path.tokenize('/').last()
        : original.host == 'maven.twelveiterations.com' ? 'twelve'
        : original.host == 'maven.fabricmc.net' ? 'fabric'
        : original.host == 'api.modrinth.com' ? 'modrinth'
        : original.host == 'fixture.invalid' ? 'loom' : 'empty'
    repo.url = uri(base + '/' + route + '/')
    repo.allowInsecureProtocol = true // Loopback-only failure simulation.
}
tasks.register('verifyRouting') {
    doLast {
        def resolve = { coordinate ->
            def configuration = configurations.detachedConfiguration(dependencies.create(coordinate))
            configuration.transitive = false
            configuration.singleFile
        }
        def hashes = [:]
        @COORDINATES@.each { coordinate ->
            hashes[coordinate] = java.security.MessageDigest.getInstance('SHA-256')
                .digest(resolve(coordinate).bytes).encodeHex().toString()
        }
        def failures = [:]
        ['com.tterrag.registrate:Registrate:MC1.21-1.3.0+67',
         'net.fabricmc:missing-required-artifact:1'].each { coordinate ->
            try {
                resolve(coordinate)
                throw new GradleException('Required unavailable artifact unexpectedly resolved: ' + coordinate)
            } catch (org.gradle.api.artifacts.ResolveException expected) {
                def trace = new StringWriter()
                expected.printStackTrace(new PrintWriter(trace))
                failures[coordinate] = trace.toString()
            }
        }
        file('receipt.json').text = groovy.json.JsonOutput.toJson([hashes: hashes, failures: failures])
    }
}
'''
                script = script.replace('@BASE@', json.dumps(f'http://127.0.0.1:{server.server_port}'))
                script = script.replace('@COORDINATES@', json.dumps(list(coordinates)))
                (directory/'build.gradle').write_text(script)
                environment = dict(os.environ, GRADLE_USER_HOME=str(directory/'gradle-home'))
                process = subprocess.run([GRADLE, 'verifyRouting', '--no-daemon', '--console=plain', '--max-workers=1',
                    '-Dorg.gradle.jvmargs=-Xmx256m', '-Dorg.gradle.parallel=false'], cwd=directory,
                    env=environment, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
                self.assertEqual(0, process.returncode, process.stdout)
                receipt = json.loads((directory/'receipt.json').read_text())
                self.assertEqual(hashes, receipt['hashes'])
                self.assertIn('521', receipt['failures']['com.tterrag.registrate:Registrate:MC1.21-1.3.0+67'])
                self.assertIn('Could not find', receipt['failures']['net.fabricmc:missing-required-artifact:1'])
                snapshots = [path for path in requests if path.startswith(('/snapshots/', '/mirror/'))]
                self.assertTrue(snapshots, requests)
                self.assertTrue(any(path.startswith('/snapshots/com/tterrag/registrate/') for path in snapshots), requests)
                self.assertTrue(all('/com/tterrag/registrate/' in path for path in snapshots), snapshots)
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == '__main__':
    unittest.main()
