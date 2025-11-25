use anyhow::{Context, Result};
use futures_util::{SinkExt, StreamExt};
use mdns_sd::{ServiceDaemon, ServiceEvent};
use rand::seq::SliceRandom;
use serde::Deserialize;
use std::net::IpAddr;
use std::time::Duration;
use tokio::time::timeout;
use tokio_tungstenite::{connect_async, tungstenite::Message};
use url::Url;

const SERVICE_TYPE: &str = "_uh._tcp.local.";
const DISCOVERY_TIMEOUT_SECS: u64 = 5;

#[derive(Debug, Clone)]
struct UhService {
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

#[tokio::main]
async fn main() -> Result<()> {
    println!("UH CLI - WebSocket Random Number Client");
    println!("========================================\n");

    // Discover services
    let services = discover_services().await?;

    if services.is_empty() {
        println!("No UH services found on the network.");
        println!("Make sure the UH Android app is running and advertising on the same network.");
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

    // Connect and receive
    connect_and_receive(selected).await?;

    Ok(())
}

/// Discover UH services via mDNS.
/// Returns list of discovered services after timeout.
async fn discover_services() -> Result<Vec<UhService>> {
    println!("Discovering services ({}s timeout)...\n", DISCOVERY_TIMEOUT_SECS);

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
                        let service = UhService {
                            name: info.get_fullname().to_string(),
                            host: info.get_hostname().to_string(),
                            port: info.get_port(),
                            addresses,
                        };
                        println!("  Found: {} at {}:{}", service.name, service.host, service.port);
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

/// Connect to selected service and receive messages.
async fn connect_and_receive(service: &UhService) -> Result<()> {
    // Use first available address
    let address = service
        .addresses
        .first()
        .context("Service has no addresses")?;

    let ws_url = format!("ws://{}:{}/", address, service.port);
    println!("Connecting to {}...", ws_url);

    let url = Url::parse(&ws_url).context("Invalid WebSocket URL")?;
    let (ws_stream, _) = connect_async(url)
        .await
        .context("Failed to connect to WebSocket server")?;

    println!("Connected!\n");
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

/// Parse and display received message.
fn handle_message(text: &str) {
    match serde_json::from_str::<RandomMessage>(text) {
        Ok(msg) if msg.msg_type == "random" => {
            // Format timestamp as human-readable time
            let datetime = chrono::DateTime::from_timestamp_millis(msg.timestamp)
                .map(|dt| dt.format("%H:%M:%S%.3f").to_string())
                .unwrap_or_else(|| msg.timestamp.to_string());

            println!("[{}] Random: {}", datetime, msg.value);
        }
        Ok(_) => {
            println!("Unknown message type: {}", text);
        }
        Err(_) => {
            println!("Raw message: {}", text);
        }
    }
}
