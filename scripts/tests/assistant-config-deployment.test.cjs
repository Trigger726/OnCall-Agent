const fs=require('node:fs'),test=require('node:test'),assert=require('node:assert/strict');
const read=f=>fs.readFileSync(f,'utf8');
test('Compose AI activation is opt-in but actually reads the supplied environment',()=>{
  const compose=read('docker-compose.yml');
  assert.match(compose,/^      AI_ENABLED: \$\{AI_ENABLED:-false\}$/m);
  assert.match(compose,/^      DASHSCOPE_API_KEY: \$\{DASHSCOPE_API_KEY:-disabled\}$/m);
  assert.doesNotMatch(compose,/^      AI_ENABLED: ["']?false["']?$/m);
});
test('backend defaults remain offline and key/activation are separate deployment bindings',()=>{
  const config=read('src/main/resources/application.yml');
  assert.match(config,/api-key: \$\{DASHSCOPE_API_KEY:disabled\}/);
  assert.match(config,/enabled: \$\{AI_ENABLED:false\}/);
  const guide=read('web/src/components/AssistantConfigurationDialog.vue');
  assert.match(guide,/只设置密钥不会打开模型/);
  assert.match(guide,/重新创建应用容器/);
  assert.match(guide,/不是在线密钥编辑器/);
});
