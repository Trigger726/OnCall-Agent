const {test}=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {metrics}=require('../verify-runbook-document-diversity-packaged.cjs');
test('duplicate chunks consume slots but cannot multiply document recall or DCG',()=>{
  const result=metrics([{relevantKeys:['a','b','c'],returned:['a','a','a']}]);
  assert.equal(result.duplicateSlots,2);assert.equal(result.recallAt3,0.333333);assert.equal(result.precisionAt3,0.333333);
  assert.equal(result.hitAt3,1);assert.equal(result.mrr,1);assert.ok(result.ndcgAt3<0.5);
});
test('three distinct relevant documents fill the same budget',()=>{
  const result=metrics([{relevantKeys:['a','b','c'],returned:['a','b','c']}]);
  assert.equal(result.duplicateSlots,0);assert.equal(result.recallAt3,1);assert.equal(result.ndcgAt3,1);
});
test('negative queries are disclosed separately, not averaged as perfect relevance',()=>{
  const result=metrics([{relevantKeys:[],returned:[]},{relevantKeys:[],returned:['wrong']}]);
  assert.equal(result.judged,0);assert.equal(result.recallAt3,null);assert.equal(result.negative,2);assert.equal(result.negativeWithResults,1);
});
test('frozen dataset has four equal strata and genuine multi-section books',()=>{
  const fixture=JSON.parse(fs.readFileSync(require.resolve('../fixtures/runbook-document-diversity-cases.json')));
  assert.equal(fixture.cases.length,32);assert.equal(new Set(fixture.cases.map(row=>row.id)).size,32);
  const groups=Object.groupBy(fixture.cases,row=>row.group);assert.equal(Object.keys(groups).length,4);
  for(const rows of Object.values(groups))assert.equal(rows.length,8);
  assert.ok(fixture.documents.some(doc=>doc.sections.length>=6));
  assert.ok(fixture.documents.some(doc=>!doc.publish));assert.ok(fixture.documents.some(doc=>!doc.roles.includes('ON_CALL')));
});
