import {test} from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {runInNewContext} from 'node:vm'
import {transform} from 'esbuild'
const read=p=>readFileSync(new URL('../src/'+p,import.meta.url),'utf8')
const resource=read('views/CmdbView.vue'),dashboard=read('views/DashboardView.vue'),auth=read('stores/auth.ts')
const compile=async text=>(await transform(text,{loader:'ts'})).code
const code=await compile(resource.slice(resource.indexOf('async function load('),resource.indexOf('function search()')))
const deferred=()=>{let resolve,reject;const promise=new Promise((a,b)=>{resolve=a;reject=b});return{promise,resolve,reject}}
function fixture(api){const f={api,Error,URLSearchParams,listSeq:0,detailSeq:0,current:()=>true,route:{query:{}},q:{value:''},type:{value:''},page:{value:1},resources:{value:[]},total:{value:0},detail:{value:null},error:{value:''},detailError:{value:''},loading:{value:false},detailLoading:{value:false}};runInNewContext(code,f);return f}
test('resource search sends server query and page rather than filtering first 100',async()=>{let url;const f=fixture(async u=>{url=u;return{items:[],total:105}});f.q.value=' %_ & ';f.page.value=6;await f.load();const q=new URLSearchParams(url.split('?')[1]);assert.equal(q.get('q'),'%_ &');assert.equal(q.get('page'),'6');assert.equal(f.total.value,105)})
test('late resource detail cannot overwrite a newer selection',async()=>{const slow=deferred();const f=fixture(u=>u.endsWith('/1')?slow.promise:Promise.resolve({resource:{id:2}}));const pending=f.selectResource(1);await f.selectResource(2);slow.resolve({resource:{id:1}});await pending;assert.equal(f.detail.value.resource.id,2)})
test('failed resource read is explicit and does not leave stale detail',async()=>{const f=fixture(async()=>{throw Error('offline')});f.detail.value={resource:{id:1}};await f.load();assert.equal(f.error.value,'offline');assert.equal(f.detail.value,null);assert.equal(f.loading.value,false)})
test('late resource list cannot replace newer filter results',async()=>{const slow=deferred();let n=0;const f=fixture(()=>++n===1?slow.promise:Promise.resolve({items:[],total:2}));const pending=f.load();await f.load();slow.resolve({items:[],total:999});await pending;assert.equal(f.total.value,2)})
test('changed login discards pending detail',async()=>{const slow=deferred(),f=fixture(()=>slow.promise);const p=f.selectResource(1);f.current=()=>false;slow.resolve({resource:{id:1}});await p;assert.equal(f.detail.value,null)})
const homeCode=await compile(dashboard.slice(dashboard.indexOf('async function load()'),dashboard.indexOf('onMounted(load)')))
test('home retains successful data when current roster fails',async()=>{const f={api:async u=>{if(u==='/on-call/current')throw Error('roster offline');return u==='/dashboard'?{activeIncidents:4}:{items:[{id:1}]}},loadSeq:0,token:'t',localStorage:{getItem:()=> 't'},loading:{value:false},error:{value:''},dashboard:{value:null},incidents:{value:[]},onCall:{value:[]},incidentError:{value:false},shiftError:{value:false},refreshedAt:{value:''}};runInNewContext(homeCode,f);await f.load();assert.equal(f.dashboard.value.activeIncidents,4);assert.equal(f.incidents.value.length,1);assert.equal(f.shiftError.value,true);assert.match(f.error.value,/值班信息读取失败/);assert.equal(f.loading.value,false)})
const storedCode=await compile(auth.slice(auth.indexOf('function readStoredUser()'),auth.indexOf('const state =')))
for(const value of ['{','{"displayName":3}','{"id":1,"username":"admin","displayName":null,"roleCode":"ADMIN"}'])test('invalid stored identity recovers to login: '+value,()=>{let removed=false;const f={localStorage:{getItem:()=>value,removeItem:()=>{removed=true}}};runInNewContext(storedCode,f);assert.equal(f.readStoredUser(),null);assert.equal(removed,true)})
