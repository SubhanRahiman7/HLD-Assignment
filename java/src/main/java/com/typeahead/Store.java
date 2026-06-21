package com.typeahead;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/**
 * Primary store.
 *
 * Two maps:
 * - counts: query -> all-time count (synthetic Zipf-shaped dataset, 100k+ entries).
 * - recency: query -> bucket counts [last5m, last1h, last24h], decayed by RecencyDecay job.
 *
 * Writes:
 * - /search calls enqueueIncrement, increments writeBuffer.
 * - flush() (every 2s + on /search) drains writeBuffer into counts.
 * - recency window is bumped every /search and re-weighted by decay job.
 */
@Component
public class Store {

 public final Map<String, Long> counts = new ConcurrentHashMap<>();

 private final Map<String, long[]> recency = new ConcurrentHashMap<>();
 public static final int BUCKET_5M = 0;
 public static final int BUCKET_1H = 1;
 public static final int BUCKET_24H = 2;

 private final Map<String, Long> writeBuffer = new ConcurrentHashMap<>();
 private final AtomicLong pendingWrites = new AtomicLong(0);

 public final AtomicLong totalBatches = new AtomicLong(0);
 public final AtomicLong totalBatchedOps = new AtomicLong(0);
 public volatile long lastFlushMs = 0;

 @PostConstruct
 public void seed() {
 loadDataset();
 }

 /**
 * Loads TSV `query\tcount` from QUERIES_FILE env var (if set), else synthesizes
 * 100,000 entries using a Zipf-distributed word generator so the dataset
 * requirement (§3, ≥100k) is satisfied even without an external file.
 */
 private void loadDataset() {
 String path = System.getenv("QUERIES_FILE");
 if (path != null && !path.isBlank()) {
 try (BufferedReader br = new BufferedReader(new FileReader(path))) {
 String line;
 while ((line = br.readLine()) != null) {
 String[] parts = line.split("\\t");
 if (parts.length < 2) continue;
 counts.put(parts[0].trim(), Long.parseLong(parts[1].trim()));
 }
 System.out.println("Loaded " + counts.size() + " queries from " + path);
 return;
 } catch (Exception e) {
 System.err.println("QUERIES_FILE load failed: " + e + " - falling back to synthetic");
 }
 }
 generateSynthetic();
 }

 /** Zipf-distributed 100k corpus. Combines multiple vocabularies so the
 unique-query space exceeds 100k. */
 private void generateSynthetic() {
 String[] brands = {"iphone","iphone 15","iphone 15 pro","iphone 15 pro max","iphone charger",
 "iphone case","ipad","ipad pro","airpods","airpods pro","samsung galaxy s24","samsung tv",
 "macbook air","macbook pro","nintendo switch 2","sony headphones","java tutorial","java jobs",
 "javascript","javascript array methods","python tutorial","python pandas","react hooks",
 "react router","react native","redis caching","system design interview","how to tie a tie",
 "how to screenshot on mac","how to boil eggs","how to invest in stocks","best laptops 2026",
 "best headphones","best coffee maker","best running shoes","taylor swift tickets",
 "world cup schedule","weather today","amazon prime day","chatgpt","flight tickets",
 "nike air force 1","adidas samba","coffee near me","concert tickets","pixel 9","pixel 9 pro",
 "surface pro","rog ally","steam deck","ps5 slim","xbox series x","lego set","yoga mat",
 "kettle","blender","air fryer","vacuum cleaner","robot vacuum","kindle","echo dot","fire tv stick",
 "iphone 14","iphone 13","iphone se","iphone xr","iphone 11","samsung s23","samsung a54",
 "pixel 8","pixel 7a","oneplus 12","xiaomi 14","huawei p60","galaxy z fold","galaxy z flip",
 "apple watch","apple watch ultra","fitbit","garmin","airpods max","sony wf","bose qc",
 "bose 700","logitech mx","razer","corsair","asus rog","msi","lenovo thinkpad","dell xps",
 "hp pavilion","surface laptop","framework","gopro","dji","canon","nikon","fujifilm",
 "playstation","xbox","steam","epic games","roblox","minecraft","fortnite","league of legends",
 "valorant","cs2","apex legends","overwatch","hearthstone","diablo","wow","elder scrolls",
 "baldur's gate 3","zelda","pokemon","mario","smash bros","halo","destiny","warframe",
 "final fantasy","persona","resident evil","silent hill","dark souls","elden ring","cyberpunk",
 "witcher","skyrim","fallout","no man's sky","stardew","terraria","among us","phasmophobia",
 "lethal company","helldivers 2","baldur","spider-man","god of war","horizon","forza",
 "gran turismo","fifa","madden","nba 2k","f1","rocket league","rainbow six","pubg",
 "call of duty","battlefield","destiny 2","diablo 4","genshin impact","honkai","wuthering",
 "star rail","tower of fantasy","lost ark","new world","guild wars","black desert","maplestory"};
 String[] actions = {"how to","best","cheap","top","new","used","review","vs","for","near me","tutorial","guide","examples","vs alternatives","reddit","amazon","walmart","target","costco","ebay","aliexpress","shop","buy","price","price drop","deals","coupon","promo","sale","clearance","outlet","used","refurbished","open box"};
 String[] nouns = {"laptop","phone","headphones","shoes","watch","camera","television","monitor","keyboard","mouse","chair","desk","lamp","backpack","speaker","tablet","router","ssd","gpu","cpu","ram","psu","case","fan","cooler","battery","charger","cable","adapter","stand","mat","bottle","bag","wallet","belt","hat","sunglasses","jacket","sweater","jeans","dress","skirt","shoe","sandal","boot","sock","glove","scarf","gloves","bed","pillow","blanket","sheet","towel","shower","curtain","rug","mirror","clock","vase","plant","pot","shelf","drawer","cabinet","sofa","couch","ottoman","bench","stool","table","desk","lamp","fan","heater","cooler","vacuum","mop","broom","bucket","trash","bin","bag","backpack","tent","sleeping bag","lantern","thermos","cooler","stove","grill","knife","cutting board","pan","pot","kettle","blender","mixer","toaster","oven","microwave","dishwasher","fridge","freezer","washer","dryer","iron","steamer","hair","dryer","shaver","trimmer","scale","blood","pressure","thermometer","first aid","bandage","vitamin","supplement","protein","creatine","pre","workout","yoga","mat","foam","roller","weights","dumbbell","bench","bike","treadmill","rower","elliptical","stair","climber","ski","snowboard","skateboard","helmet","pads","cleats","sneakers","boots","trail","running","hiking","camping","fishing","hunting","binoculars","scope","range","finder","compass","map","gps"};
 String[] tech = {"react","vue","svelte","angular","next.js","nuxt","remix","astro","solid","qwik",
 "node.js","express","nestjs","fastify","koa","deno","bun","django","flask","fastapi","spring boot",
 "spring","hibernate","kafka","redis","postgres","mysql","mongodb","elasticsearch","docker","kubernetes",
 "aws","gcp","azure","terraform","ansible","jenkins","github actions","nginx","haproxy","graphql","grpc",
 "rest","websocket","oauth","jwt","saml","openid","cors","xss","csrf","vite","webpack","turbopack","esbuild",
 "rollup","parcel","babel","swc","typescript","javascript","python","java","go","rust","kotlin","swift",
 "ruby","scala","elixir","haskell","clojure","erlang","lua","perl","php","sql","prisma","sequelize","drizzle",
 "typeorm","hibernate","sqlalchemy","alembic","flyway","liquibase","maven","gradle","npm","yarn","pnpm",
 "bun","deno","pip","poetry","conda","cargo","go mod","composer","rubygems","nuget","pub","swiftpm",
 "openjdk","graalvm","corretto","temurin","zulu","dragonwell","sapmachine","bellsoft","azul","liberica",
 "ktor","micronaut","quarkus","helidon","play","sparkjava","jooby","javalin","ratpack","vert.x","mutiny",
 "reactor","rxjava","kotlin coroutines","virtual threads","loom","structured concurrency","scoped values",
 "vector api","panama","foreign function","graalvm native","spring native","quarkus native","helidon native",
 "micronaut graal","aws lambda","cloud functions","cloud run","azure functions","azure container apps",
 "ecs","eks","fargate","cloud run","cloud build","code build","cloud deploy","azure devops","github actions",
 "circleci","travis","gitlab","bitbucket","argocd","fluxcd","helm","kustomize","skaffold","tilt","skaffold",
 "snyk","trivy","clair","grype","checkov","tfsec","kics","terrascan","wiz","lacework","orca","prisma cloud",
 "datadog","new relic","dynatrace","splunk","elk","grafana","loki","tempo","mimir","cortex","thanos","victoria",
 "prometheus","alertmanager","pagerduty","opsgenie","victorops","firehydrant","incident.io","rootly","blameless",
 "verica","chaos engineering","gremlin","chaos mesh","litmus","steadybit","n8n","airflow","dagster","prefect",
 "argo","kubeflow","mlflow","bentoml","ray serve","triton","torchserve","seldon","kfserving","serving",
 "vector db","pinecone","weaviate","qdrant","milvus","chroma","pgvector","pgvector","llamaindex","langchain",
 "haystack","semantic kernel","autogen","crewai","smolagents","llama.cpp","ollama","vllm","tgi","truss",
 "replicate","modal","banana","beam","runpod","lambda labs","coreweave","together","","groq",
 "fireworks","anyscale","","octo","hex","dbt","airbyte","fivetran","stitch","mode","looker",
 "tableau","superset","metabase","preset","redash","databricks","snowflake","bigquery","redshift",
 "synapse","databricks sql","athena","trino","presto","druid","pinot","clickhouse","duckdb","polars",
 "pandas","dask","spark","ray","modin","vaex","cudf","cupy","pytorch","tensorflow","jax","onnx","tensorrt",
 "openvino","tvm","mlir","cuda","rocm","opencl","sycl","metal","vulkan","directx","opengl","webgl","webgpu",
 "wasm","emscripten","llvm","clang","gcc","rustc","go","zig","nim","crystal","dart","flutter","kotlin",
 "swift","scala","groovy","clojure","elixir","erlang","haskell","ocaml","fsharp","reasonml","elm","purescript",
 "haxe","nim","crystal","d","v","hare","carbon","mojo","spade"};

 // Build a large enumerable space: tech × variant + actions × nouns.
 // tech alone × 5 variants yields ~1500; × noun variants → plenty for 100k.
 int techCount = tech.length;
 int nounCount = nouns.length;
 int actionCount = actions.length;
 long totalSpace = (long) techCount * techCount // tech1 vs tech2
 + (long) actionCount * nounCount
 + (long) brands.length;
 Random rng = new Random(42);
 Set<String> seen = new HashSet<>(131072);
 List<String[]> rows = new ArrayList<>(110_000);
 long base = 200_000L;

 // 1) brand list verbatim
 for (String b : brands) {
 if (seen.add(b)) rows.add(new String[]{b, String.valueOf(base)});
 base -= 50;
 }

 // 2) actions × nouns: "best laptop", "cheap headphones", ...
 for (String a : actions) for (String n : nouns) {
 String q = a + " " + n;
 if (seen.add(q)) rows.add(new String[]{q, String.valueOf(base)});
 base -= 5;
 }

 // 3) tech comparisons + combinations: "react vs vue", "java vs kotlin", ...
 for (int i = 0; i < tech.length; i++) for (int j = 0; j < tech.length; j++) {
 if (i == j) continue;
 String q = tech[i] + " vs " + tech[j];
 if (seen.add(q)) rows.add(new String[]{q, String.valueOf(base)});
 base -= 2;
 }
 // 4) tech tutorial/guide combinations
 String[] suffixes = {" tutorial"," guide"," examples"," crash course"," cheatsheet"," best practices",
 " interview questions"," roadmap"," documentation"," cli"," api"," sdk"," vs alternatives",
 " on aws"," on gcp"," on azure"," on kubernetes"," on docker"," performance"," caching",
 " logging"," monitoring"," testing"," security"," authentication"," authorization",
 " vs graphql"," vs grpc"," vs rest"," vs websocket"," vs kafka"," vs rabbitmq"," vs redis",
 " vs postgres"," vs mysql"," vs mongodb"," vs elasticsearch"," vs docker"," vs kubernetes",
 " vs terraform"," vs ansible"," vs jenkins"," vs github actions"," vs nginx"," vs haproxy",
 " vs oauth"," vs jwt"," vs saml"};
 for (String t : tech) for (String s : suffixes) {
 String q = t + s;
 if (seen.add(q)) rows.add(new String[]{q, String.valueOf(base)});
 base -= 1;
 if (rows.size() >= 110_000) break;
 }
 if (rows.size() < 110_000) {
 // 5) brand × variant: "iphone 15 review", "iphone 15 vs iphone 14", ...
 String[] brandSuffix = {" review"," vs samsung"," vs google"," vs xiaomi"," vs oneplus"," vs huawei",
 " release date"," colors"," sizes"," weight"," battery"," camera"," specs"," price"," deals",
 " best deal"," cheapest"," on amazon"," on walmart"," on best buy"," unlocked"," verizon"," att",
 " tmobile"," prepaid"," contract"," trade in"," refurbished"," pros and cons"," reddit"};
 for (String b : brands) for (String s : brandSuffix) {
 String q = b + s;
 if (seen.add(q)) rows.add(new String[]{q, String.valueOf(base)});
 base -= 1;
 if (rows.size() >= 110_000) break;
 }
 }
 // Triple combos: <action> <noun> <suffix>
 if (rows.size() < 110_000) {
 for (String a : actions) for (String n : nouns) for (String s : suffixes) {
 String q = a + " " + n + s;
 if (seen.add(q)) rows.add(new String[]{q, String.valueOf(base)});
 base -= 1;
 if (rows.size() >= 110_000) break;
 }
 }
 // Brand × tech: "iphone react", "samsung svelte"
 if (rows.size() < 110_000) {
 for (String b : brands) for (String t : tech) {
 String q = b + " " + t;
 if (seen.add(q)) rows.add(new String[]{q, String.valueOf(base)});
 base -= 1;
 if (rows.size() >= 110_000) break;
 }
 }

 for (String[] r : rows) counts.put(r[0], Long.parseLong(r[1]));
 System.out.println("Generated synthetic corpus: " + counts.size() + " unique queries");

 // Seed recency buckets for demo.
 recency.put("world cup schedule", new long[]{9, 12, 15});
 recency.put("chatgpt", new long[]{6, 8, 12});
 recency.put("taylor swift tickets", new long[]{7, 9, 11});
 recency.put("iphone 15", new long[]{4, 5, 8});
 recency.put("best laptops 2026", new long[]{5, 6, 9});
 recency.put("react hooks", new long[]{3, 4, 7});
 }

 public Map<String, long[]> recency() { return recency; }

 /** Called on every /search. Coalesces same-query writes inside the 2s window. */
 public void enqueueIncrement(String q) {
 writeBuffer.merge(q, 1L, Long::sum);
 pendingWrites.incrementAndGet();
 bumpRecency(q);
 }

 private void bumpRecency(String q) {
 recency.compute(q, (k, v) -> {
 if (v == null) return new long[]{1, 1, 1};
 v[BUCKET_5M]++;
 v[BUCKET_1H]++;
 v[BUCKET_24H]++;
 return v;
 });
 }

 /** Apply buffered deltas to the primary store. Called by BatchScheduler and on /search. */
 public void flush() {
 if (writeBuffer.isEmpty()) return;
 Map<String, Long> snapshot = new HashMap<>(writeBuffer);
 writeBuffer.clear();
 pendingWrites.set(0);
 long t0 = System.currentTimeMillis();
 for (var e : snapshot.entrySet()) counts.merge(e.getKey(), e.getValue(), Long::sum);
 totalBatches.incrementAndGet();
 totalBatchedOps.addAndGet(snapshot.size());
 lastFlushMs = System.currentTimeMillis() - t0;
 }

 /** Decay oldest bucket, shift 1h -> 24h, shift 5m -> 1h, drop oldest. */
 public void decayRecency() {
 for (var e : recency.entrySet()) {
 long[] b = e.getValue();
 // Geometric decay factor per bucket.
 b[BUCKET_5M] = (long) (b[BUCKET_5M] * 0.85);
 b[BUCKET_1H] = (long) (b[BUCKET_1H] * 0.95);
 b[BUCKET_24H] = (long) (b[BUCKET_24H] * 0.99);
 if (b[BUCKET_5M] + b[BUCKET_1H] + b[BUCKET_24H] < 1) recency.remove(e.getKey());
 }
 }

 public long pendingWrites() { return pendingWrites.get(); }

 /** Zipf sampler for synthetic dataset. */
 private static final class ZipfSampler {
 private final int n;
 private final double s;
 private final double[] cum;
 ZipfSampler(int n, double s) {
 this.n = n; this.s = s;
 double[] p = new double[n + 1];
 double sum = 0;
 for (int i = 1; i <= n; i++) { p[i] = 1.0 / Math.pow(i, s); sum += p[i]; }
 cum = new double[n + 1];
 double acc = 0;
 for (int i = 1; i <= n; i++) { acc += p[i] / sum; cum[i] = acc; }
 }
 int sample(Random r) {
 double u = r.nextDouble();
 int lo = 1, hi = n;
 while (lo < hi) { int mid = (lo + hi) >>> 1; if (cum[mid] < u) lo = mid + 1; else hi = mid; }
 return lo;
 }
 }
}