use anyhow::{anyhow, Context, Result};
use clap::{Parser, Subcommand};
use futures_util::{SinkExt, StreamExt};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use rand::seq::SliceRandom;
use serde::{Deserialize, Serialize};
use std::net::IpAddr;
use std::time::Duration;
use tokio::time::timeout;
use tokio_tungstenite::{connect_async, tungstenite::Message};
use url::Url;

const USE_IPV4_ONLY: bool = true;
const SERVICE_TYPE: &str = "_echowire._tcp.local.";
const DISCOVERY_TIMEOUT_SECS: u64 = 5;
const CONFIG_RESPONSE_TIMEOUT_SECS: u64 = 3;

#[derive(Debug, Clone)]
struct EchoWireService {
    name: String,
    host: String,
    port: u16,
    addresses: Vec<IpAddr>,
}

// ── protocol messages ─────────────────────────────────────────────────────────

#[derive(Debug, Deserialize)]
struct HelloMessage {
    device_name: String,
    protocol_version: u32,
    timestamp: i64,
}

#[derive(Debug, Deserialize)]
struct PartialResultMessage {
    text: String,
    timestamp: i64,
}

#[derive(Debug, Deserialize)]
struct Alternative {
    text: String,
    confidence: f32,
}

#[derive(Debug, Deserialize)]
struct FinalResultMessage {
    alternatives: Vec<Alternative>,
    best_text: String,
    best_confidence: f32,
    language: String,
    sentence_type: Option<String>,
    timestamp: i64,
    session_duration_ms: i64,
    speech_duration_ms: i64,
}

#[derive(Debug, Deserialize)]
struct RecognitionErrorMessage {
    error_code: i32,
    error_message: String,
    timestamp: i64,
    auto_restart: bool,
}

#[derive(Debug, Serialize)]
struct ConfigureRequest {
    configure: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    value: Option<String>,
}

#[derive(Debug, Deserialize)]
struct ConfigureResponse {
    configure: String,
    value: String,
}

// ── CLI ───────────────────────────────────────────────────────────────────────

#[derive(Parser)]
#[command(name = "echowirecli")]
#[command(about = "EchoWire WebSocket Client", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand)]
enum Commands {
    /// Listen to speech results (default)
    Listen,
    /// Set a config value  e.g. echowirecli set name=MyDevice
    Set {
        #[arg(value_parser = parse_key_value)]
        config: (String, String),
    },
    /// Get a config value  e.g. echowirecli get name
    Get { key: String },
}

fn parse_key_value(s: &str) -> Result<(String, String), String> {
    let pos = s.find('=').ok_or_else(|| format!("no '=' in '{}'", s))?;
    let key = s[..pos].trim().to_string();
    if key.is_empty() { return Err("empty key".to_string()); }
    let value = s[pos + 1..].trim().trim_matches('"').trim_matches('\'').to_string();
    Ok((key, value))
}

// ── main ──────────────────────────────────────────────────────────────────────

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();

    println!("EchoWire CLI");
    println!("============\n");

    let services = discover_services().await?;
    if services.is_empty() {
        println!("No EchoWire services found. Make sure the app is running on the same network.");
        return Ok(());
    }

    println!("Discovered {} service(s):\n", services.len());
    for (i, svc) in services.iter().enumerate() {
        println!("  [{}] {}  {}:{}", i + 1, svc.name, svc.host, svc.port);
    }
    println!();

    let selected = services.choose(&mut rand::thread_rng()).context("no service")?;
    println!("Connecting to: {}\n", selected.name);

    match cli.command.unwrap_or(Commands::Listen) {
        Commands::Listen => listen_to_service(selected).await?,
        Commands::Set { config: (key, value) } => send_configure_set(selected, &key, &value).await?,
        Commands::Get { key } => send_configure_get(selected, &key).await?,
    }

    Ok(())
}

// ── discovery ─────────────────────────────────────────────────────────────────

async fn discover_services() -> Result<Vec<EchoWireService>> {
    println!("Discovering services ({}s timeout)...", DISCOVERY_TIMEOUT_SECS);
    if USE_IPV4_ONLY { println!("IPv4 only\n"); }

    let mdns = ServiceDaemon::new().context("mDNS daemon failed")?;
    let receiver = mdns.browse(SERVICE_TYPE).context("mDNS browse failed")?;
    let mut services = Vec::new();

    let task = async {
        while let Ok(event) = receiver.recv_async().await {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let addresses: Vec<IpAddr> = info.get_addresses().iter().copied().collect();
                    if !addresses.is_empty() {
                        println!("  Found: {} at {}:{}", info.get_fullname(), info.get_hostname(), info.get_port());
                        services.push(EchoWireService {
                            name: info.get_fullname().to_string(),
                            host: info.get_hostname().to_string(),
                            port: info.get_port(),
                            addresses,
                        });
                    }
                }
                ServiceEvent::ServiceRemoved(_, name) => { services.retain(|s| s.name != name); }
                ServiceEvent::SearchStopped(_) => break,
                _ => {}
            }
        }
    };

    let _ = timeout(Duration::from_secs(DISCOVERY_TIMEOUT_SECS), task).await;
    mdns.shutdown().context("mDNS shutdown failed")?;
    println!();
    Ok(services)
}

// ── helpers ───────────────────────────────────────────────────────────────────

fn select_address(addresses: &[IpAddr]) -> Result<&IpAddr> {
    if USE_IPV4_ONLY {
        addresses.iter().find(|a| matches!(a, IpAddr::V4(_))).context("No IPv4 address")
    } else {
        addresses.first().context("No addresses")
    }
}

fn format_addr(addr: &IpAddr) -> String {
    match addr {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => format!("[{}]", v6),
    }
}

fn fmt_ts(ms: i64) -> String {
    chrono::DateTime::from_timestamp_millis(ms)
        .map(|dt| dt.format("%H:%M:%S%.3f").to_string())
        .unwrap_or_else(|| ms.to_string())
}

// ── listen ────────────────────────────────────────────────────────────────────

async fn listen_to_service(service: &EchoWireService) -> Result<()> {
    let addr = select_address(&service.addresses)?;
    let url = Url::parse(&format!("ws://{}:{}/", format_addr(addr), service.port))?;
    let (ws, _) = connect_async(url).await.context("WebSocket connect failed")?;
    println!("Connected. Listening (Ctrl+C to stop):\n");

    let (_write, mut read) = ws.split();
    let ctrl_c = tokio::signal::ctrl_c();
    tokio::pin!(ctrl_c);

    loop {
        tokio::select! {
            msg = read.next() => match msg {
                Some(Ok(Message::Text(text))) => handle_message(&text),
                Some(Ok(Message::Ping(_) | Message::Pong(_))) => {}
                Some(Ok(Message::Close(_))) | None => { println!("\nConnection closed"); break; }
                Some(Err(e)) => { println!("\nError: {}", e); break; }
                _ => {}
            },
            _ = &mut ctrl_c => { println!("\nShutting down..."); break; }
        }
    }
    Ok(())
}

// ── message handler ───────────────────────────────────────────────────────────

fn handle_message(text: &str) {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(text) else {
        println!("Raw: {}", text);
        return;
    };

    match value.get("type").and_then(|t| t.as_str()) {
        Some("hello") => {
            if let Ok(m) = serde_json::from_value::<HelloMessage>(value) {
                println!("[{}] Device: \"{}\" protocol v{}", fmt_ts(m.timestamp), m.device_name, m.protocol_version);
            }
        }
        Some("partial_result") => {
            if let Ok(m) = serde_json::from_value::<PartialResultMessage>(value) {
                if !m.text.is_empty() {
                    print!("{} ", m.text);
                    let _ = std::io::Write::flush(&mut std::io::stdout());
                }
            }
        }
        Some("final_result") => {
            if let Ok(m) = serde_json::from_value::<FinalResultMessage>(value) {
                let ts = fmt_ts(m.timestamp);
                let type_tag = m.sentence_type.as_deref().unwrap_or("");
                let conf_pct = (m.best_confidence * 100.0) as u32;
                println!(); // end partial line
                if type_tag.is_empty() {
                    println!("[{}] [{}] \"{}\" ({}%  {}ms)", ts, m.language, m.best_text, conf_pct, m.speech_duration_ms);
                } else {
                    println!("[{}] [{}][{}] \"{}\" ({}%  {}ms)", ts, m.language, type_tag, m.best_text, conf_pct, m.speech_duration_ms);
                }
                if m.alternatives.len() > 1 {
                    for (i, alt) in m.alternatives.iter().enumerate().skip(1) {
                        println!("      alt{}: \"{}\" ({:.0}%)", i + 1, alt.text, alt.confidence * 100.0);
                    }
                }
            }
        }
        Some("recognition_error") => {
            if let Ok(m) = serde_json::from_value::<RecognitionErrorMessage>(value) {
                let restart = if m.auto_restart { " (auto-restart)" } else { "" };
                println!("\n[{}] ERROR {}: {}{}", fmt_ts(m.timestamp), m.error_code, m.error_message, restart);
            }
        }
        _ => {
            // configure response or unknown
            if let Ok(r) = serde_json::from_value::<ConfigureResponse>(value) {
                println!("Config: {} = {}", r.configure, r.value);
            } else {
                println!("Unknown: {}", text);
            }
        }
    }
}

// ── configure ─────────────────────────────────────────────────────────────────

type WsSink = futures_util::stream::SplitSink<
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
    Message,
>;
type WsStream = futures_util::stream::SplitStream<
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>,
>;

async fn connect_ws(service: &EchoWireService) -> Result<(WsSink, WsStream)> {
    let addr = select_address(&service.addresses)?;
    let url = Url::parse(&format!("ws://{}:{}/", format_addr(addr), service.port))?;
    let (ws, _) = connect_async(url).await.context("WebSocket connect failed")?;
    println!("Connected to ws://{}:{}/\n", format_addr(addr), service.port);
    Ok(ws.split())
}

async fn send_configure_set(service: &EchoWireService, key: &str, value: &str) -> Result<()> {
    let (mut write, mut read) = connect_ws(service).await?;
    let req = serde_json::to_string(&ConfigureRequest { configure: key.to_string(), value: Some(value.to_string()) })?;
    println!("Sending: {}", req);
    write.send(Message::Text(req)).await?;
    wait_config_response(&mut read, key).await
}

async fn send_configure_get(service: &EchoWireService, key: &str) -> Result<()> {
    let (mut write, mut read) = connect_ws(service).await?;
    let req = serde_json::to_string(&ConfigureRequest { configure: key.to_string(), value: None })?;
    println!("Sending: {}", req);
    write.send(Message::Text(req)).await?;
    wait_config_response(&mut read, key).await
}

async fn wait_config_response(read: &mut WsStream, key: &str) -> Result<()> {
    let fut = async {
        while let Some(msg) = read.next().await {
            match msg {
                Ok(Message::Text(text)) => {
                    if let Ok(r) = serde_json::from_str::<ConfigureResponse>(&text) {
                        if r.configure == key {
                            println!("{} = {}", r.configure, r.value);
                            return Ok(());
                        }
                    }
                }
                Ok(Message::Close(_)) => return Err(anyhow!("Server closed connection")),
                Err(e) => return Err(anyhow!("WebSocket error: {}", e)),
                _ => {}
            }
        }
        Err(anyhow!("Connection closed without response"))
    };

    timeout(Duration::from_secs(CONFIG_RESPONSE_TIMEOUT_SECS), fut)
        .await
        .map_err(|_| anyhow!("Timeout waiting for config response"))?
}
