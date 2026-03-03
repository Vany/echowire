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

#[derive(Debug, Deserialize)]
struct RandomMessage {
    #[serde(rename = "type")]
    msg_type: String,
    value: i64,
    timestamp: i64,
}

#[derive(Debug, Deserialize)]
struct SpeechMessage {
    #[serde(rename = "type")]
    msg_type: String,
    text: String,
    embedding: Vec<f32>,
    language: String,
    timestamp: i64,
    segment_start: i64,
    segment_end: i64,
    processing_time_ms: i64,
    audio_duration_ms: i64,
    rtf: f32,
}

#[derive(Debug, Deserialize)]
struct AudioStatusMessage {
    #[serde(rename = "type")]
    msg_type: String,
    listening: bool,
    audio_level: f32,
    timestamp: i64,
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

#[derive(Parser)]
#[command(name = "echowirecli")]
#[command(about = "EchoWire WebSocket Client - Control and monitor EchoWire services", long_about = None)]
struct Cli {
    #[command(subcommand)]
    command: Option<Commands>,
}

#[derive(Subcommand)]
enum Commands {
    /// Listen to messages from an EchoWire service (default behavior)
    Listen,

    /// Set a configuration value on an EchoWire service
    ///
    /// Example: echowirecli set name=MyDevice
    Set {
        /// Configuration key=value pair (e.g., name=MyDevice)
        #[arg(value_parser = parse_key_value)]
        config: (String, String),
    },

    /// Get a configuration value from an EchoWire service
    ///
    /// Example: echowirecli get name
    Get {
        /// Configuration key to retrieve
        key: String,
    },
}

/// Parse key=value pair from command line argument.
fn parse_key_value(s: &str) -> Result<(String, String), String> {
    let pos = s
        .find('=')
        .ok_or_else(|| format!("Invalid KEY=VALUE format: no '=' found in '{}'", s))?;

    let key = s[..pos].trim().to_string();
    let value = s[pos + 1..].trim().to_string();

    if key.is_empty() {
        return Err("Key cannot be empty".to_string());
    }

    // Remove quotes if present
    let value = value.trim_matches('"').trim_matches('\'').to_string();

    Ok((key, value))
}

#[tokio::main]
async fn main() -> Result<()> {
    let cli = Cli::parse();

    println!("EchoWire CLI - WebSocket Client");
    println!("================================\n");

    // Discover services
    let services = discover_services().await?;

    if services.is_empty() {
        println!("No EchoWire services found on the network.");
        println!(
            "Make sure the EchoWire Android app is running and advertising on the same network."
        );
        return Ok(());
    }

    // List discovered services
    println!("Discovered {} service(s):\n", services.len());
    for (idx, service) in services.iter().enumerate() {
        println!("  [{}] {}", idx + 1, service.name);
        println!("      Host: {}", service.host);
        println!("      Port: {}", service.port);
        println!("      Addresses: {:?}\n", service.addresses);
    }

    // Select random service
    let selected = services
        .choose(&mut rand::thread_rng())
        .context("Failed to select random service")?;

    println!("Randomly selected: {}\n", selected.name);

    // Execute command
    match cli.command.unwrap_or(Commands::Listen) {
        Commands::Listen => {
            listen_to_service(selected).await?;
        }
        Commands::Set {
            config: (key, value),
        } => {
            send_configure_set(selected, &key, &value).await?;
        }
        Commands::Get { key } => {
            send_configure_get(selected, &key).await?;
        }
    }

    Ok(())
}

/// Discover EchoWire services via mDNS.
/// Returns list of discovered services after timeout.
async fn discover_services() -> Result<Vec<EchoWireService>> {
    println!(
        "Discovering services ({}s timeout)...\n",
        DISCOVERY_TIMEOUT_SECS
    );
    println!("ℹ️  Looking for service type: {}", SERVICE_TYPE);
    if USE_IPV4_ONLY {
        println!("ℹ️  IPv4-only mode enabled\n");
    }

    let mdns = ServiceDaemon::new().context("Failed to create mDNS daemon")?;
    let receiver = mdns
        .browse(SERVICE_TYPE)
        .context("Failed to browse for services")?;

    let mut services = Vec::new();

    // Collect services for the discovery timeout period
    let discovery_task = async {
        while let Ok(event) = receiver.recv_async().await {
            match event {
                ServiceEvent::ServiceResolved(info) => {
                    let addresses: Vec<IpAddr> = info.get_addresses().iter().copied().collect();

                    if !addresses.is_empty() {
                        let service = EchoWireService {
                            name: info.get_fullname().to_string(),
                            host: info.get_hostname().to_string(),
                            port: info.get_port(),
                            addresses,
                        };
                        println!(
                            "  Found: {} at {}:{}",
                            service.name, service.host, service.port
                        );
                        services.push(service);
                    }
                }
                ServiceEvent::ServiceRemoved(_, fullname) => {
                    println!("  Removed: {}", fullname);
                    services.retain(|s| s.name != fullname);
                }
                ServiceEvent::SearchStarted(_) => {
                    // Search started, continue
                }
                ServiceEvent::SearchStopped(_) => {
                    // Search stopped, we're done
                    break;
                }
                _ => {}
            }
        }
    };

    // Wait for discovery timeout
    let _ = timeout(Duration::from_secs(DISCOVERY_TIMEOUT_SECS), discovery_task).await;

    mdns.shutdown().context("Failed to shutdown mDNS daemon")?;

    println!();
    Ok(services)
}

/// Select appropriate IP address based on USE_IPV4_ONLY setting.
fn select_address(addresses: &[IpAddr]) -> Result<&IpAddr> {
    if USE_IPV4_ONLY {
        addresses
            .iter()
            .find(|addr| matches!(addr, IpAddr::V4(_)))
            .context("No IPv4 address found (USE_IPV4_ONLY is enabled)")
    } else {
        addresses.first().context("Service has no addresses")
    }
}

/// Format IP address for URL (IPv6 needs brackets).
fn format_address_for_url(addr: &IpAddr) -> String {
    match addr {
        IpAddr::V4(v4) => v4.to_string(),
        IpAddr::V6(v6) => format!("[{}]", v6),
    }
}

/// Connect to selected service and listen to broadcast messages.
async fn listen_to_service(service: &EchoWireService) -> Result<()> {
    let address = select_address(&service.addresses)?;

    let addr_str = format_address_for_url(address);
    let ws_url = format!("ws://{}:{}/", addr_str, service.port);

    println!("ℹ️  Connecting to: {}", ws_url);
    println!("ℹ️  IP Address: {}", address);
    println!("ℹ️  Port: {}", service.port);
    println!("ℹ️  Service: {}\n", service.name);

    let url = Url::parse(&ws_url).context("Invalid WebSocket URL")?;
    let (ws_stream, _) = connect_async(url)
        .await
        .context("Failed to connect to WebSocket server")?;

    println!("✅ Connected!\n");
    println!("Receiving messages (Ctrl+C to stop):\n");

    let (_write, mut read) = ws_stream.split();

    // Setup Ctrl+C handler
    let ctrl_c = tokio::signal::ctrl_c();
    tokio::pin!(ctrl_c);

    // Receive messages
    loop {
        tokio::select! {
            msg = read.next() => {
                match msg {
                    Some(Ok(Message::Text(text))) => {
                        handle_message(&text);
                    }
                    Some(Ok(Message::Ping(_))) => {
                        // WebSocket library handles pong automatically
                    }
                    Some(Ok(Message::Pong(_))) => {
                        // Pong received
                    }
                    Some(Ok(Message::Close(_))) => {
                        println!("\nServer closed connection");
                        break;
                    }
                    Some(Err(e)) => {
                        println!("\nError receiving message: {}", e);
                        break;
                    }
                    None => {
                        println!("\nConnection closed");
                        break;
                    }
                    _ => {}
                }
            }
            _ = &mut ctrl_c => {
                println!("\n\nShutting down...");
                break;
            }
        }
    }

    Ok(())
}

/// Send configure message to set a value and display response.
async fn send_configure_set(service: &EchoWireService, key: &str, value: &str) -> Result<()> {
    let address = select_address(&service.addresses)?;

    let addr_str = format_address_for_url(address);
    let ws_url = format!("ws://{}:{}/", addr_str, service.port);

    println!("ℹ️  Connecting to: {}", ws_url);
    println!("ℹ️  IP Address: {}", address);
    println!("ℹ️  Port: {}", service.port);

    let url = Url::parse(&ws_url).context("Invalid WebSocket URL")?;
    let (ws_stream, _) = connect_async(url)
        .await
        .context("Failed to connect to WebSocket server")?;

    println!("✅ Connected!\n");

    let (mut write, mut read) = ws_stream.split();

    // Send configure message
    let request = ConfigureRequest {
        configure: key.to_string(),
        value: Some(value.to_string()),
    };

    let request_json =
        serde_json::to_string(&request).context("Failed to serialize configure request")?;

    println!("Sending: {}", request_json);
    write
        .send(Message::Text(request_json))
        .await
        .context("Failed to send configure message")?;

    // Wait for response with timeout
    let response_future = async {
        while let Some(msg) = read.next().await {
            match msg {
                Ok(Message::Text(text)) => {
                    if let Ok(response) = serde_json::from_str::<ConfigureResponse>(&text) {
                        if response.configure == key {
                            return Ok(response);
                        }
                    }
                }
                Ok(Message::Close(_)) => {
                    return Err(anyhow!("Server closed connection"));
                }
                Err(e) => {
                    return Err(anyhow!("Error receiving message: {}", e));
                }
                _ => {}
            }
        }
        Err(anyhow!("Connection closed without response"))
    };

    match timeout(
        Duration::from_secs(CONFIG_RESPONSE_TIMEOUT_SECS),
        response_future,
    )
    .await
    {
        Ok(Ok(response)) => {
            println!("\nConfiguration updated:");
            println!("  {} = {}", response.configure, response.value);
            Ok(())
        }
        Ok(Err(e)) => Err(anyhow!("Failed to receive response: {}", e)),
        Err(_) => Err(anyhow!("Timeout waiting for response")),
    }
}

/// Send configure message to get a value and display response.
async fn send_configure_get(service: &EchoWireService, key: &str) -> Result<()> {
    let address = select_address(&service.addresses)?;

    let addr_str = format_address_for_url(address);
    let ws_url = format!("ws://{}:{}/", addr_str, service.port);

    println!("ℹ️  Connecting to: {}", ws_url);
    println!("ℹ️  IP Address: {}", address);
    println!("ℹ️  Port: {}", service.port);

    let url = Url::parse(&ws_url).context("Invalid WebSocket URL")?;
    let (ws_stream, _) = connect_async(url)
        .await
        .context("Failed to connect to WebSocket server")?;

    println!("✅ Connected!\n");

    let (mut write, mut read) = ws_stream.split();

    // Send configure message without value (get operation)
    let request = ConfigureRequest {
        configure: key.to_string(),
        value: None,
    };

    let request_json =
        serde_json::to_string(&request).context("Failed to serialize configure request")?;

    println!("Sending: {}", request_json);
    write
        .send(Message::Text(request_json))
        .await
        .context("Failed to send configure message")?;

    // Wait for response with timeout
    let response_future = async {
        while let Some(msg) = read.next().await {
            match msg {
                Ok(Message::Text(text)) => {
                    if let Ok(response) = serde_json::from_str::<ConfigureResponse>(&text) {
                        if response.configure == key {
                            return Ok(response);
                        }
                    }
                }
                Ok(Message::Close(_)) => {
                    return Err(anyhow!("Server closed connection"));
                }
                Err(e) => {
                    return Err(anyhow!("Error receiving message: {}", e));
                }
                _ => {}
            }
        }
        Err(anyhow!("Connection closed without response"))
    };

    match timeout(
        Duration::from_secs(CONFIG_RESPONSE_TIMEOUT_SECS),
        response_future,
    )
    .await
    {
        Ok(Ok(response)) => {
            println!("\nCurrent configuration:");
            println!("  {} = {}", response.configure, response.value);
            Ok(())
        }
        Ok(Err(e)) => Err(anyhow!("Failed to receive response: {}", e)),
        Err(_) => Err(anyhow!("Timeout waiting for response")),
    }
}

/// Parse and display received message.
fn handle_message(text: &str) {
    // Try to parse as speech message first
    if let Ok(msg) = serde_json::from_str::<SpeechMessage>(text) {
        if msg.msg_type == "speech" {
            let datetime = chrono::DateTime::from_timestamp_millis(msg.timestamp)
                .map(|dt| dt.format("%H:%M:%S%.3f").to_string())
                .unwrap_or_else(|| msg.timestamp.to_string());

            // Display text with language and timing
            println!(
                "[{}] Speech [{}] ({:.0}ms, RTF={:.2}): \"{}\"",
                datetime, msg.language, msg.processing_time_ms as f32, msg.rtf, msg.text
            );

            // Show embedding preview (first 5 values)
            let embedding_preview: Vec<String> = msg
                .embedding
                .iter()
                .take(5)
                .map(|v| format!("{:.4}", v))
                .collect();
            println!(
                "      Embedding: [{}...] ({} dims)",
                embedding_preview.join(", "),
                msg.embedding.len()
            );
            return;
        }
    }

    // Try to parse as audio status message
    if let Ok(msg) = serde_json::from_str::<AudioStatusMessage>(text) {
        if msg.msg_type == "audio_status" {
            let datetime = chrono::DateTime::from_timestamp_millis(msg.timestamp)
                .map(|dt| dt.format("%H:%M:%S%.3f").to_string())
                .unwrap_or_else(|| msg.timestamp.to_string());

            let status = if msg.listening { "LISTENING" } else { "IDLE" };
            let level_bar = "█".repeat((msg.audio_level * 20.0) as usize);
            println!(
                "[{}] Audio: {} | Level: {:<20} {:.1}%",
                datetime,
                status,
                level_bar,
                msg.audio_level * 100.0
            );
            return;
        }
    }

    // Try to parse as random message (legacy)
    if let Ok(msg) = serde_json::from_str::<RandomMessage>(text) {
        if msg.msg_type == "random" {
            let datetime = chrono::DateTime::from_timestamp_millis(msg.timestamp)
                .map(|dt| dt.format("%H:%M:%S%.3f").to_string())
                .unwrap_or_else(|| msg.timestamp.to_string());
            println!("[{}] Random: {}", datetime, msg.value);
            return;
        }
    }

    // Try to parse as configure response
    if let Ok(response) = serde_json::from_str::<ConfigureResponse>(text) {
        println!("Config: {} = {}", response.configure, response.value);
        return;
    }

    // Unknown message format
    println!("Raw message: {}", text);
}
