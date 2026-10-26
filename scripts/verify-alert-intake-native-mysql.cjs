// Reuse the established fresh-server runner and its mandatory execution/count/cleanup gates.
const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const assert = require('node:assert/strict'), { spawnSync } = require('node:child_process');
const args = process.argv.slice(2), root = path.resolve(__dirname, '..');
const sourceIndex = args.indexOf('--source-root');
const sourceRoot = sourceIndex < 0 ? root : path.resolve(args[sourceIndex + 1]);
if (sourceIndex >= 0) args.splice(sourceIndex, 2);
assert.ok(fs.existsSync(path.join(sourceRoot, 'pom.xml')), 'Source root must contain pom.xml');
let code = fs.readFileSync(path.join(root, 'scripts/verify-scoped-evaluation-native-mysql.cjs'), 'utf8');
const rootAnchor = "const root = path.resolve(__dirname, '..');";
assert.equal(code.split(rootAnchor).length, 2, 'Shared harness root changed');
code = code.replace(rootAnchor, 'const root = ' + JSON.stringify(sourceRoot) + ';');
const start = code.indexOf("    const classes=['RunbookScoped");
const end = code.indexOf('    console.log(JSON.stringify({phase:', start);
assert.ok(start >= 0 && end > start, 'Shared harness class selection changed');
code = code.slice(0, start) + "    const classes=['AlertIntakeAuthorizationIntegrationTest','MySqlAlertIntakeAuthorizationIntegrationTest'];\n    const expectedTests=18;\n" + code.slice(end);
const xmlAnchor = 'TEST-org.trigger.opspilot.runbook.${name}.xml';
assert.equal(code.split(xmlAnchor).length, 2, 'Shared harness report path changed');
code = code.replace(xmlAnchor, 'TEST-org.trigger.opspilot.alert.${name}.xml');
// These contracts have no schema-upgrade scenario; retain all failure, skip, count and normal-shutdown assertions.
const schemaStart = code.indexOf('    report.schemaVersions=await sql(');
const schemaEnd = code.indexOf("    report.status='PASS';", schemaStart);
assert.ok(schemaStart >= 0 && schemaEnd > schemaStart, 'Shared harness schema gate changed');
code = code.slice(0, schemaStart) + code.slice(schemaEnd);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'opspilot-intake-runner-'));
const file = path.join(dir, 'native-mysql.cjs'); fs.writeFileSync(file, code);
const result = spawnSync(process.execPath, [file, ...args], { cwd: root, stdio: 'inherit', windowsHide: true });
if (result.error) throw result.error;
process.exitCode = result.status === 0 ? 0 : 1;
