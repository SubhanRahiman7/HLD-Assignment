// Node primary store — mirrors Store.java.
// Keyed by query; buckets: [last5m, last1h, last24h] decayed by RecencyDecay job.

function makeBuckets() { return [0, 0, 0]; }

const DATA = []; // {q, c} — final source of truth for queries/counts
const index = new Map(); // query.lower -> DATA entry (fast lookup)
const recency = new Map(); // query.lower -> [5m, 1h, 24h]

const writeBuffer = new Map(); // query.lower -> delta
let pendingWrites = 0;
let totalBatches = 0;
let totalBatchedOps = 0;
let lastFlushMs = 0;

function applyCount(q, delta) {
 const e = index.get(q.toLowerCase());
 if (!e) return;
 e.c += delta;
}
function enqueueIncrement(q) {
 const k = q.toLowerCase();
 writeBuffer.set(k, (writeBuffer.get(k) || 0) + 1);
 pendingWrites++;
 bumpRecency(q);
}
function bumpRecency(q) {
 const k = q.toLowerCase();
 const v = recency.get(k);
 if (!v) recency.set(k, [1, 1, 1]);
 else { v[0]++; v[1]++; v[2]++; }
}
function flushBatch() {
 if (!writeBuffer.size) return;
 const t0 = process.hrtime.bigint();
 for (const [q, delta] of writeBuffer) {
 applyCount(q, delta);
 }
 writeBuffer.clear();
 pendingWrites = 0;
 lastFlushMs = Number(process.hrtime.bigint() - t0) / 1e6;
 totalBatches++;
 totalBatchedOps += writeBuffer.size;
}
function decayRecency() {
 for (const [k, v] of recency) {
 v[0] = Math.floor(v[0] * 0.85);
 v[1] = Math.floor(v[1] * 0.95);
 v[2] = Math.floor(v[2] * 0.99);
 if (v[0] + v[1] + v[2] < 1) recency.delete(k);
 }
}
function seed() {
 generateSynthetic();
 recency.set('world cup schedule', [9, 12, 15]);
 recency.set('chatgpt', [6, 8, 12]);
 recency.set('taylor swift tickets', [7, 9, 11]);
 recency.set('iphone 15', [4, 5, 8]);
 recency.set('best laptops 2026', [5, 6, 9]);
 recency.set('react hooks', [3, 4, 7]);
}
function zipfSample(rng, n) {
 const theta = 1.07;
 let z = 0;
 for (let i = 1; i <= n; i++) z += 1 / Math.pow(i, theta);
 const u = rng.nextDouble();
 let x = 0, cdf = 0;
 for (let i = 1; i <= n; i++) {
 cdf += 1 / Math.pow(i, theta) / z;
 if (u <= cdf) { x = i; break; }
 }
 return x - 1;
}
function generateSynthetic() {
 const brands = ['iphone','iphone 15','iphone 15 pro','iphone 15 pro max','iphone charger',
 'iphone case','ipad','ipad pro','airpods','airpods pro','samsung galaxy s24','samsung tv',
 'macbook air','macbook pro','nintendo switch 2','sony headphones','java tutorial','java jobs',
 'javascript','javascript array methods','python tutorial','python pandas','react hooks',
 'react router','react native','redis caching','system design interview','how to tie a tie',
 'how to screenshot on mac','how to boil eggs','how to invest in stocks','best laptops 2026',
 'best headphones','best coffee maker','best running shoes','taylor swift tickets',
 'world cup schedule','weather today','amazon prime day','chatgpt','flight tickets',
 'nike air force 1','adidas samba','coffee near me','concert tickets','pixel 9','pixel 9 pro',
 'surface pro','rog ally','steam deck','ps5 slim','xbox series x','lego set','yoga mat',
 'kettle','blender','air fryer','vacuum cleaner','robot vacuum','kindle','echo dot','fire tv stick',
 'iphone 14','iphone 13','iphone se','iphone xr','iphone 11','samsung s23','samsung a54',
 'pixel 8','pixel 7a','oneplus 12','xiaomi 14','huawei p60','galaxy z fold','galaxy z flip',
 'apple watch','apple watch ultra','fitbit','garmin','airpods max','sony wf','bose qc',
 'bose 700','logitech mx','razer','corsair','asus rog','msi','lenovo thinkpad','dell xps',
 'hp pavilion','surface laptop','framework','gopro','dji','canon','nikon','fujifilm',
 'playstation','xbox','steam','epic games','roblox','minecraft','fortnite','league of legends',
 'valorant','cs2','apex legends','overwatch','hearthstone','diablo','wow','elder scrolls',
 'baldur gate 3','zelda','pokemon','mario','smash bros','halo','destiny','warframe',
 'final fantasy','persona','resident evil','silent hill','dark souls','elden ring','cyberpunk',
 'witcher','skyrim','fallout','no man sky','stardew','terraria','among us','phasmophobia',
 'lethal company','helldivers 2','baldur','spider-man','god of war','horizon','forza',
 'gran turismo','fifa','madden','nba 2k','f1','rocket league','rainbow six','pubg',
 'call of duty','battlefield','destiny 2','diablo 4','genshin impact','honkai','wuthering',
 'star rail','tower of fantasy','lost ark','new world','guild wars','black desert','maplestory'];
 const actions = ['how to','best','cheap','top','new','used','review','vs','for','near me','tutorial','guide','examples','vs alternatives','reddit','amazon','walmart','target','costco','ebay','aliexpress','shop','buy','price','price drop','deals','coupon','promo','sale','clearance','outlet','used','refurbished','open box'];
 const nouns = ['laptop','phone','headphones','shoes','watch','camera','television','monitor','keyboard','mouse','chair','desk','lamp','backpack','speaker','tablet','router','ssd','gpu','cpu','ram','psu','case','fan','cooler','battery','charger','cable','adapter','stand','mat','bottle','bag','wallet','belt','hat','sunglasses','jacket','sweater','jeans','dress','skirt','shoe','sandal','boot','sock','glove','scarf','gloves','bed','pillow','blanket','sheet','towel','shower','curtain','rug','mirror','clock','vase','plant','pot','shelf','drawer','cabinet','sofa','couch','ottoman','bench','stool','table','desk','lamp','fan','heater','cooler','vacuum','mop','broom','bucket','trash','bin','bag','backpack','tent','sleeping bag','lantern','thermos','cooler','stove','grill','knife','cutting board','pan','pot','kettle','blender','mixer','toaster','oven','microwave','dishwasher','fridge','freezer','washer','dryer','iron','steamer','hair','dryer','shaver','trimmer','scale','blood','pressure','thermometer','first aid','bandage','vitamin','supplement','protein','creatine','pre','workout','yoga','mat','foam','roller','weights','dumbbell','bench','bike','treadmill','rower','elliptical','stair','climber','ski','snowboard','skateboard','helmet','pads','cleats','sneakers','boots','trail','running','hiking','camping','fishing','hunting','binoculars','scope','range','finder','compass','map','gps'];
 const tech = ['react','vue','svelte','angular','next.js','nuxt','remix','astro','solid','qwik',
 'node.js','express','nestjs','fastify','koa','deno','bun','django','flask','fastapi','spring boot',
 'spring','hibernate','kafka','redis','postgres','mysql','mongodb','elasticsearch','docker','kubernetes',
 'aws','gcp','azure','terraform','ansible','jenkins','github actions','nginx','haproxy','graphql','grpc',
 'rest','websocket','oauth','jwt','saml','openid','cors','xss','csrf','vite','webpack','turbopack','esbuild',
 'rollup','parcel','babel','swc','typescript','javascript','python','java','go','rust','kotlin','swift',
 'ruby','scala','elixir','haskell','clojure','erlang','lua','perl','php','sql','prisma','sequelize','drizzle',
 'typeorm','hibernate','sqlalchemy','alembic','flyway','liquibase','maven','gradle','npm','yarn','pnpm',
 'bun','deno','pip','poetry','conda','cargo','go mod','composer','rubygems','nuget','pub','swiftpm',
 'openjdk','graalvm','corretto','temurin','zulu','dragonwell','sapmachine','bellsoft','azul','liberica',
 'ktor','micronaut','quarkus','helidon','play','sparkjava','jooby','javalin','ratpack','vert.x','mutiny',
 'reactor','rxjava','kotlin coroutines','virtual threads','loom','structured concurrency','scoped values',
 'vector api','panama','foreign function','graalvm native','spring native','quarkus native','helidon native',
 'micronaut graal','aws lambda','cloud functions','cloud run','azure functions','azure container apps',
 'ecs','eks','fargate','cloud run','cloud build','code build','cloud deploy','azure devops','github actions',
 'circleci','travis','gitlab','bitbucket','argocd','fluxcd','helm','kustomize','skaffold','tilt','skaffold',
 'snyk','trivy','clair','grype','checkov','tfsec','kics','terrascan','wiz','lacework','orca','prisma cloud',
 'datadog','new relic','dynatrace','splunk','elk','grafana','loki','tempo','mimir','cortex','thanos','victoria',
 'prometheus','alertmanager','pagerduty','opsgenie','victorops','firehydrant','incident.io','rootly','blameless',
 'verica','chaos engineering','gremlin','chaos mesh','litmus','steadybit','n8n','airflow','dagster','prefect',
 'argo','kubeflow','mlflow','bentoml','ray serve','triton','torchserve','seldon','kfserving','serving',
 'vector db','pinecone','weaviate','qdrant','milvus','chroma','pgvector','llamaindex','langchain',
 'haystack','semantic kernel','autogen','crewai','smolagents','llama.cpp','ollama','vllm','tgi','truss',
 'replicate','modal','banana','beam','runpod','lambda labs','coreweave','together','','groq',
 'fireworks','anyscale','','octo','hex','dbt','airbyte','fivetran','stitch','mode','looker',
 'tableau','superset','metabase','preset','redash','databricks','snowflake','bigquery','redshift',
 'synapse','databricks sql','athena','trino','presto','druid','pinot','clickhouse','duckdb','polars',
 'pandas','dask','spark','ray','modin','vaex','cudf','cupy','pytorch','tensorflow','jax','onnx','tensorrt',
 'openvino','tvm','mlir','cuda','rocm','opencl','sycl','metal','vulkan','directx','opengl','webgl','webgpu',
 'wasm','emscripten','llvm','clang','gcc','rustc','go','zig','nim','crystal','dart','flutter','kotlin',
 'swift','scala','groovy','clojure','elixir','erlang','haskell','ocaml','fsharp','reasonml','elm','purescript',
 'haxe','nim','crystal','d','v','hare','carbon','mojo','spade'];
 const suffixes = [' tutorial',' guide',' examples',' crash course',' cheatsheet',' best practices',
 ' interview questions',' roadmap',' documentation',' cli',' api',' sdk',' vs alternatives',
 ' on aws',' on gcp',' on azure',' on kubernetes',' on docker',' performance',' caching',
 ' logging',' monitoring',' testing',' security',' authentication',' authorization',
 ' vs graphql',' vs grpc',' vs rest',' vs websocket',' vs kafka',' vs rabbitmq',' vs redis',
 ' vs postgres',' vs mysql',' vs mongodb',' vs elasticsearch',' vs docker',' vs kubernetes',
 ' vs terraform',' vs ansible',' vs jenkins',' vs github actions',' vs nginx',' vs haproxy',
 ' vs oauth',' vs jwt',' vs saml'];
 const brandSuffix = [' review',' vs samsung',' vs google',' vs xiaomi',' vs oneplus',' vs huawei',
 ' release date',' colors',' sizes',' weight',' battery',' camera',' specs',' price',' deals',
 ' best deal',' cheapest',' on amazon',' on walmart',' on best buy',' unlocked',' verizon',' att',
 ' tmobile',' prepaid',' contract',' trade in',' refurbished',' pros and cons',' reddit'];
 const rng = new seededRng(42);
 const seen = new Set();
 let base = 200_000;
 function add(q) {
 const k = q.toLowerCase();
 if (seen.has(k)) return;
 seen.add(k);
 DATA.push({ q: k, c: Math.max(50, base--) });
 index.set(k, DATA[DATA.length - 1]);
 }
 for (const b of brands) add(b);
 for (const a of actions) for (const n of nouns) add(`${a} ${n}`);

 outer:
 for (let i = 0; i < tech.length; i++) {
 for (let j = i + 1; j < tech.length; j++) {
 add(`${tech[i]} vs ${tech[j]}`);
 if (DATA.length >= 110_000) break outer;
 }
 }

 outer2:
 if (DATA.length < 110_000) {
 for (const t of tech) {
 for (const s of suffixes) {
 add(t + s);
 if (DATA.length >= 110_000) break outer2;
 }
 }
 }

 outer3:
 if (DATA.length < 110_000) {
 for (const b of brands) {
 for (const s of brandSuffix) {
 add(b + s);
 if (DATA.length >= 110_000) break outer3;
 }
 }
 }
 outer4:
 if (DATA.length < 110_000) {
 // Triple combos: <action> <noun> <suffix>
 for (const a of actions) {
 for (const n of nouns) {
 for (const s of suffixes) {
 add(`${a} ${n}${s}`);
 if (DATA.length >= 110_000) break outer4;
 }
 }
 }
 }
 outer5:
 if (DATA.length < 110_000) {
 // brand + tech: "iphone react", "samsung svelte"
 for (const b of brands) {
 for (const t of tech) {
 add(`${b} ${t}`);
 if (DATA.length >= 110_000) break outer5;
 }
 }
 }
 console.log('Generated synthetic corpus:', DATA.length, 'unique queries');
}

function seededRng(seed) {
 let s = seed;
 return { nextDouble() { s = (s * 1664525 + 1013904223) >>> 0; return s / 0x100000000; } };
}

module.exports = {
 DATA,
 index,
 recency,
 enqueueIncrement,
 applyCount,
 flushBatch,
 decayRecency,
 seed,
 pendingWrites: () => pendingWrites,
 totalBatches: () => totalBatches,
 totalBatchedOps: () => totalBatchedOps,
 lastFlushMs: () => lastFlushMs,
};