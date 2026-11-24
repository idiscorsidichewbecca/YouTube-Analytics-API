// RIFERIMENTO per il builder di ChatGPT Sites (non server standalone eseguibile finché
// il Site non lo integra nel proprio runtime MCP e nella gestione identità owner-only).
// Il builder deve proteggere tutti gli strumenti con l'autenticazione del proprietario.
// Dipendenze richieste dal framework: @modelcontextprotocol/sdk, zod.
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { z } from "zod";

const backend = process.env.CHEWBECCA_BACKEND_URL;
const secret = process.env.CHEWBECCA_PLUGIN_KEY;
if (!backend || !/^https:\/\/[a-z0-9.-]+\/?$/i.test(backend) || !secret) {
  throw new Error("Configura i segreti server-side CHEWBECCA_BACKEND_URL e CHEWBECCA_PLUGIN_KEY");
}
const base = backend.replace(/\/$/, "");
async function backendCall(path: string, method: "GET" | "POST", body?: unknown) {
  const response = await fetch(`${base}/internal/chatgpt/shorts${path}`, {
    method,
    headers: { Authorization: `Bearer ${secret}`, ...(body ? {"Content-Type": "application/json"} : {}) },
    ...(body ? {body: JSON.stringify(body)} : {}),
    redirect: "error"
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`Backend Chewbecca: HTTP ${response.status} - ${text.slice(0,300)}`);
  return JSON.parse(text);
}
const content = (result: unknown) => ({ content: [{type: "text" as const, text: JSON.stringify(result)}] });
export const chewbeccaServer = new McpServer({name: "Chewbecca Studio", version:"1.0.0"});
chewbeccaServer.registerTool("chewbecca_recommend_slot", {
  title: "Analizza gli Shorts e consiglia la pubblicazione",
  description: "Valuta gli ultimi due mesi di Shorts e consiglia il miglior slot nelle prossime 48 ore",
  inputSchema: {}, annotations: {readOnlyHint:true, openWorldHint:false}
}, async () => content(await backendCall("/recommendation", "GET")));
chewbeccaServer.registerTool("chewbecca_prepare_short", {
  title: "Prepara la bozza YouTube Short",
  description: "Crea una bozza con titolo e descrizione preparati nella chat; non pubblica",
  inputSchema: {brief:z.string().min(30).max(10000), title:z.string().min(1).max(100),
                description:z.string().min(1).max(5000), madeForKids:z.boolean()},
  annotations: {readOnlyHint:false, destructiveHint:false, openWorldHint:false}
}, async (args) => content(await backendCall("/drafts", "POST", args)));
chewbeccaServer.registerTool("chewbecca_get_status", {
  title: "Controlla lo stato della bozza",
  description: "Controlla se un video è in bozza, in caricamento, programmato o con esito incerto",
  inputSchema: {id:z.string()}, annotations: {readOnlyHint:true, openWorldHint:false}
}, async ({id}) => content(await backendCall(`/drafts/${encodeURIComponent(id)}`, "GET")));

// I 4 campi file sono necessari per Scan Tools; solo i primi due in required.
const fileSchema = z.object({
  download_url: z.string().url(),
  file_id: z.string(),
  mime_type: z.string().optional(),
  file_name: z.string().optional()
}).strict();
chewbeccaServer.registerTool("chewbecca_schedule_short", {
  title: "Programma lo YouTube Short dopo la mia approvazione",
  description: "AZIONE DI SCRITTURA: carica il video su YouTube e programma la pubblicazione; non ritentare automaticamente",
  inputSchema: {id:z.string(), file:fileSchema, confirmationText:z.string(),
                title:z.string().optional(), description:z.string().optional(), publishAt:z.string().optional()},
  annotations: {readOnlyHint:false, destructiveHint:true, openWorldHint:true},
  _meta: {"openai/fileParams": ["file"]}
}, async ({id, ...args}) => content(await backendCall(`/drafts/${encodeURIComponent(id)}/schedule`, "POST", args)));
