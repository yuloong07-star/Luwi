// iroh sidecar v2: 端点 + 回环控制面 (HTTP) + 回环数据面 (裸 TCP) + 入站回调
//
// 分工写在 docs/Luwi-异地同步-P2P实现方案-20261022.md §5.1~§5.3:
//   sidecar 只做三件事 —— 端点与身份, 拨号/接听, 双向字节搬运; 业务帧在 JS 侧, 这里不解析
//
// 环境变量:
//   LW_PORT        控制面端口, 0 = 随机 (实际端口见 READY 行)
//   LW_DATA_PORT   数据面端口, 0 = 随机
//   LW_INBOUND     JS 侧入站回调端口, 缺省 0 = 不收 (没有回调就不接听)
//   LW_TOKEN       控制面 Bearer 与数据面首行的口令
//   LW_IDLE_MS     没有活动连接超过这么久就自己退出, 0 = 不退 (常驻档, 缺省)
//   LW_PRESET      n0 (缺省, 带 relay 与发现) | minimal (不碰 relay 与发现, 环回测试用)
//   LW_ONLINE_MS   等 online 的上限, 缺省 8000
//
// 控制面:
//   GET  /health                       免 token 的存活探测
//   GET  /status                       id / ticket / uptimeMs / alpn / conns / idle
//   GET  /v1/id                        {id, ticket}
//   POST /v1/shutdown                  应答后退出
//   POST /send?bytes=N  body=ticket    旧探针: 拨号发 N 字节
//
// 数据面 (127.0.0.1:<LW_DATA_PORT>):
//   首行 {"op":"connect","to":"<ticket|endpointId>","token":"..."}
//   回行 {"ok":true,"path":"ip|relay","rttMs":42,"remote":"[240e:…]:4433"}
//   之后这条 TCP 就是双向字节管道, 帧由 JS 写/读
//   入站: 收到对端 QUIC 双向流后, 主动连 127.0.0.1:<LW_INBOUND>, 写一行 {"peer":"<id>"}
use std::collections::HashMap;
use std::env;
use std::net::SocketAddr;
use std::sync::Arc;
use std::sync::atomic::{AtomicU64, AtomicUsize, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, Result};
use iroh::endpoint::{Connection, RecvStream, SendStream};
use iroh::{Endpoint, EndpointAddr, EndpointId, TransportAddr, endpoint::presets};
use iroh_tickets::endpoint::EndpointTicket;
use serde_json::{Value, json};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::watch;

const ALPN: &[u8] = b"lw-iroh-p2p/1";
const MAX_LINE: usize = 8 * 1024;
const CONNECT_TIMEOUT_MS: u64 = 30_000;

fn now_ms() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0)
}

struct State {
    endpoint: Endpoint,
    ticket: String,
    started: Instant,
    token: String,
    data_port: u16,
    inbound_port: u16,
    idle_ms: u64,
    active: AtomicUsize,
    total: AtomicUsize,
    last_busy_ms: AtomicU64,
    shutdown: watch::Sender<bool>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let mut args = env::args().skip(1);
    let role = args.next().unwrap_or_else(|| "daemon".to_string());
    match role.as_str() {
        "daemon" => daemon().await,
        "listen" => listen(None).await,
        // 只对端公布 IPv4 地址 + relay: 让拨号方没有 v6 可试, 逼出真正的 v4 NAT 穿越
        "listen4" => listen(Some(true)).await,
        "dial" => {
            let ticket = args.next().ok_or_else(|| anyhow!("dial needs a ticket"))?;
            let size = args.next().map(|v| v.parse::<usize>()).transpose()?.unwrap_or(8 * 1024 * 1024);
            dial(ticket, size, None).await
        }
        // 只保留 ticket 里的 IPv4 直连地址 + relay: 逼出 "NAT 对 NAT 的 v4 打洞或中转"
        "dial4" => {
            let ticket = args.next().ok_or_else(|| anyhow!("dial4 needs a ticket"))?;
            let size = args.next().map(|v| v.parse::<usize>()).transpose()?.unwrap_or(8 * 1024 * 1024);
            dial(ticket, size, Some(true)).await
        }
        // 只保留 IPv6 直连地址 + relay: 逼出 "v6 直连"
        "dial6" => {
            let ticket = args.next().ok_or_else(|| anyhow!("dial6 needs a ticket"))?;
            let size = args.next().map(|v| v.parse::<usize>()).transpose()?.unwrap_or(8 * 1024 * 1024);
            dial(ticket, size, Some(false)).await
        }
        other => Err(anyhow!("unknown role {other}")),
    }
}

fn env_u64(name: &str, fallback: u64) -> u64 {
    env::var(name).ok().and_then(|value| value.parse::<u64>().ok()).unwrap_or(fallback)
}

fn env_u16(name: &str, fallback: u16) -> u16 {
    env::var(name).ok().and_then(|value| value.parse::<u16>().ok()).unwrap_or(fallback)
}

async fn bind_endpoint() -> Result<Endpoint> {
    let preset = env::var("LW_PRESET").unwrap_or_default();
    // Endpoint::builder 对两种 preset 返回同一个 Builder 类型, 分支直接给值
    let builder = if preset == "minimal" {
        Endpoint::builder(presets::Minimal)
    } else {
        Endpoint::builder(presets::N0)
    };
    Ok(builder.alpns(vec![ALPN.to_vec()]).bind().await?)
}

async fn daemon() -> Result<()> {
    let started = Instant::now();
    let token = env::var("LW_TOKEN").unwrap_or_else(|_| "dev-token".to_string());
    let http_port = env_u16("LW_PORT", 0);
    let data_port_want = env_u16("LW_DATA_PORT", 0);
    let inbound_port = env_u16("LW_INBOUND", 0);
    let idle_ms = env_u64("LW_IDLE_MS", 0);
    let online_ms = env_u64("LW_ONLINE_MS", 8_000);

    let endpoint = bind_endpoint().await?;
    println!("[{}ms] bind ok id={}", started.elapsed().as_millis(), endpoint.id());
    // 没网时 online 可能一直不返回; 本地直连仍然可用, 所以超时就往下走
    if tokio::time::timeout(Duration::from_millis(online_ms), endpoint.online()).await.is_err() {
        println!("[{}ms] online timed out after {online_ms}ms, continuing", started.elapsed().as_millis());
    }
    let ticket = EndpointTicket::new(endpoint.addr()).to_string();
    println!("[{}ms] online ticket={ticket}", started.elapsed().as_millis());

    let http = TcpListener::bind(("127.0.0.1", http_port)).await?;
    let http_port = http.local_addr()?.port();
    let data = TcpListener::bind(("127.0.0.1", data_port_want)).await?;
    let data_port = data.local_addr()?.port();

    let (shutdown, rx) = watch::channel(false);
    let state = Arc::new(State {
        endpoint,
        ticket,
        started,
        token,
        data_port,
        inbound_port,
        idle_ms,
        active: AtomicUsize::new(0),
        total: AtomicUsize::new(0),
        last_busy_ms: AtomicU64::new(now_ms()),
        shutdown,
    });

    tokio::spawn(http_accept(http, state.clone()));
    tokio::spawn(data_accept(data, state.clone()));
    tokio::spawn(inbound_accept(state.clone()));
    tokio::spawn(idle_watch(state.clone(), rx.clone()));

    println!(
        "READY port={} dataPort={} token_len={} idleMs={} inbound={}",
        http_port,
        data_port,
        state.token.len(),
        idle_ms,
        inbound_port
    );

    let mut rx = rx;
    let _ = rx.changed().await;
    println!("shutdown requested, closing endpoint");
    state.endpoint.close().await;
    Ok(())
}

async fn http_accept(listener: TcpListener, state: Arc<State>) {
    loop {
        match listener.accept().await {
            Ok((socket, _)) => {
                let state = state.clone();
                tokio::spawn(async move {
                    if let Err(error) = handle_http(socket, state).await {
                        eprintln!("http request failed: {error:#}");
                    }
                });
            }
            Err(error) => {
                eprintln!("http accept failed: {error}");
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
}

struct Request {
    method: String,
    path: String,
    query: HashMap<String, String>,
    headers: HashMap<String, String>,
    body: Vec<u8>,
}

async fn handle_http(mut socket: TcpStream, state: Arc<State>) -> Result<()> {
    let request = read_request(&mut socket).await?;

    if request.path == "/health" {
        return respond(&mut socket, 200, "{\"ok\":true}").await;
    }

    let presented = request
        .headers
        .get("authorization")
        .and_then(|value| value.strip_prefix("Bearer "))
        .unwrap_or("");
    if !constant_time_eq(presented, &state.token) {
        return respond(&mut socket, 401, "{\"ok\":false,\"error\":\"unauthorized\"}").await;
    }

    match (request.method.as_str(), request.path.as_str()) {
        ("GET", "/status") => {
            let body = json!({
                "ok": true,
                "id": state.endpoint.id().to_string(),
                "ticket": state.ticket,
                "uptimeMs": state.started.elapsed().as_millis() as u64,
                "alpn": String::from_utf8_lossy(ALPN),
                "conns": { "active": state.active.load(Ordering::SeqCst), "total": state.total.load(Ordering::SeqCst) },
                "idleMs": state.idle_ms,
                "dataPort": state.data_port,
                "inboundPort": state.inbound_port,
            })
            .to_string();
            respond(&mut socket, 200, &body).await
        }
        ("GET", "/v1/id") => {
            let body = json!({
                "ok": true,
                "id": state.endpoint.id().to_string(),
                "ticket": state.ticket,
            })
            .to_string();
            respond(&mut socket, 200, &body).await
        }
        ("POST", "/v1/shutdown") => {
            respond(&mut socket, 200, "{\"ok\":true}").await?;
            println!("shutdown via /v1/shutdown");
            let _ = state.shutdown.send(true);
            Ok(())
        }
        ("POST", "/send") => {
            let size = request.query.get("bytes").and_then(|value| value.parse::<usize>().ok()).unwrap_or(8 * 1024 * 1024);
            let family = match request.query.get("family").map(|value| value.as_str()) {
                Some("4") => Some(true),
                Some("6") => Some(false),
                _ => None,
            };
            let ticket = String::from_utf8(request.body.clone())?;
            if ticket.trim().is_empty() {
                return respond(&mut socket, 400, "{\"ok\":false,\"error\":\"empty ticket\"}").await;
            }
            match do_send(&state.endpoint, ticket.trim(), size, family).await {
                Ok((connect_ms, send_ms, mibps, path_kind, path_rtt_ms, path_remote)) => {
                    let body = json!({
                        "ok": true,
                        "bytes": size,
                        "connectMs": connect_ms,
                        "sendMs": send_ms,
                        "mibps": mibps,
                        "path": path_kind,
                        "pathRttMs": path_rtt_ms,
                        "remote": path_remote,
                    })
                    .to_string();
                    respond(&mut socket, 200, &body).await
                }
                Err(error) => {
                    let body = json!({ "ok": false, "error": format!("{error:#}") }).to_string();
                    respond(&mut socket, 502, &body).await
                }
            }
        }
        _ => respond(&mut socket, 404, "{\"ok\":false,\"error\":\"not found\"}").await,
    }
}

fn describe_path(conn: &Connection) -> (String, u128, String) {
    let mut kind = "none".to_string();
    let mut rtt = 0u128;
    let mut remote = String::new();
    let paths = conn.paths();
    for path in &paths {
        let peer = path.remote_addr();
        eprintln!(
            "path candidate ip={} relay={} selected={} remote={peer} rtt={}ms",
            path.is_ip(),
            path.is_relay(),
            path.is_selected(),
            path.rtt().as_millis()
        );
        if path.is_selected() {
            kind = if path.is_ip() { "ip".to_string() } else if path.is_relay() { "relay".to_string() } else { "other".to_string() };
            rtt = path.rtt().as_millis();
            // TransportAddr 的 Display 带 "ip:" / "relay:" 前缀, 读数里只要地址本身
            remote = peer.to_string();
            for prefix in ["ip:", "relay:", "custom:"] {
                if let Some(stripped) = remote.strip_prefix(prefix) {
                    remote = stripped.to_string();
                    break;
                }
            }
        }
    }
    (kind, rtt, remote)
}

// 刚从 connect 回来时可能还没有 selected path, 等一小会儿再读, 让 path/rtt 有意义
async fn describe_path_settled(conn: &Connection) -> (String, u128, String) {
    for _ in 0..20 {
        let reading = describe_path(conn);
        if reading.0 != "none" {
            return reading;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    describe_path(conn)
}

async fn do_send(endpoint: &Endpoint, ticket_text: &str, size: usize, family: Option<bool>) -> Result<(u128, u128, f64, String, u128, String)> {
    let ticket: EndpointTicket = ticket_text.parse()?;
    let mut addr = ticket.endpoint_addr().clone();
    if let Some(v4_only) = family {
        let before = addr.addrs.len();
        addr.addrs.retain(|candidate| match candidate {
            TransportAddr::Ip(SocketAddr::V4(_)) => v4_only,
            TransportAddr::Ip(SocketAddr::V6(_)) => !v4_only,
            _ => true,
        });
        eprintln!("send: filtered to v4_only={v4_only}, addrs {before} -> {}", addr.addrs.len());
    }
    let connect_started = Instant::now();
    eprintln!("send: connecting");
    let conn = endpoint.connect(addr, ALPN).await?;
    let (path_kind, path_rtt_ms, path_remote) = describe_path(&conn);
    eprintln!("send: connected via {path_kind} rtt={path_rtt_ms}ms remote={path_remote}");
    let (mut send, mut recv) = conn.open_bi().await?;

    let chunk = vec![0u8; 64 * 1024];
    let send_started = Instant::now();
    let mut sent = 0usize;
    while sent < size {
        let take = chunk.len().min(size - sent);
        send.write_all(&chunk[..take]).await?;
        sent += take;
    }
    send.finish()?;
    let send_ms = send_started.elapsed().as_millis();
    let _ = recv.read_to_end(64).await;
    let connect_ms = connect_started.elapsed().as_millis();
    let mibps = (size as f64 / 1048576.0) / (send_ms as f64 / 1000.0).max(0.001);
    Ok((connect_ms, send_ms, mibps, path_kind, path_rtt_ms, path_remote))
}

// ---- 数据面 ----

async fn data_accept(listener: TcpListener, state: Arc<State>) {
    loop {
        match listener.accept().await {
            Ok((socket, _)) => {
                state.active.fetch_add(1, Ordering::SeqCst);
                state.total.fetch_add(1, Ordering::SeqCst);
                state.last_busy_ms.store(now_ms(), Ordering::SeqCst);
                let state = state.clone();
                tokio::spawn(async move {
                    if let Err(error) = handle_data(socket, state.clone()).await {
                        eprintln!("data connection failed: {error:#}");
                    }
                    state.active.fetch_sub(1, Ordering::SeqCst);
                    state.last_busy_ms.store(now_ms(), Ordering::SeqCst);
                });
            }
            Err(error) => {
                eprintln!("data accept failed: {error}");
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    }
}

async fn handle_data(socket: TcpStream, state: Arc<State>) -> Result<()> {
    let (line, leftover, mut socket) = read_line_with_rest(socket, MAX_LINE).await?;
    let request: Value = serde_json::from_slice(&line).map_err(|error| anyhow!("bad first line: {error}"))?;

    let token = request.get("token").and_then(Value::as_str).unwrap_or("");
    if !constant_time_eq(token, &state.token) {
        let reply = json!({ "ok": false, "error": "unauthorized" }).to_string() + "\n";
        let _ = socket.write_all(reply.as_bytes()).await;
        return Ok(());
    }

    let op = request.get("op").and_then(Value::as_str).unwrap_or("");
    if op != "connect" {
        let reply = json!({ "ok": false, "error": format!("unknown op {op}") }).to_string() + "\n";
        let _ = socket.write_all(reply.as_bytes()).await;
        return Ok(());
    }

    let to = request.get("to").and_then(Value::as_str).unwrap_or("").trim().to_string();
    if to.is_empty() {
        let reply = json!({ "ok": false, "error": "empty peer" }).to_string() + "\n";
        let _ = socket.write_all(reply.as_bytes()).await;
        return Ok(());
    }

    let addr = resolve_peer(&to)?;
    let connecting = state.endpoint.connect(addr, ALPN);
    let conn = match tokio::time::timeout(Duration::from_millis(CONNECT_TIMEOUT_MS), connecting).await {
        Ok(Ok(conn)) => conn,
        Ok(Err(error)) => {
            let reply = json!({ "ok": false, "error": format!("{error:#}") }).to_string() + "\n";
            let _ = socket.write_all(reply.as_bytes()).await;
            return Ok(());
        }
        Err(_) => {
            let reply = json!({ "ok": false, "error": format!("connect timed out after {CONNECT_TIMEOUT_MS}ms") }).to_string() + "\n";
            let _ = socket.write_all(reply.as_bytes()).await;
            return Ok(());
        }
    };

    let (kind, rtt, remote) = describe_path_settled(&conn).await;
    let reply = json!({ "ok": true, "path": kind, "rttMs": rtt, "remote": remote, "peer": conn.remote_id().to_string() }).to_string() + "\n";
    socket.write_all(reply.as_bytes()).await?;

    let (send, recv) = conn.open_bi().await?;
    let (up, down) = pump(socket, send, recv, leftover, &conn).await?;
    println!("data connection done: up={up} down={down} path={kind}");
    Ok(())
}

fn resolve_peer(text: &str) -> Result<EndpointAddr> {
    if let Ok(ticket) = text.parse::<EndpointTicket>() {
        return Ok(ticket.endpoint_addr().clone());
    }
    if let Ok(id) = text.parse::<EndpointId>() {
        // 只给 nodeId 时靠 LW_PRESET 的发现服务找人; minimal 档下这条一定失败
        return Ok(EndpointAddr::new(id));
    }
    Err(anyhow!("peer is neither an endpoint ticket nor an endpoint id"))
}

// 读一行 (含 '\n'), 返回 (那一行, 行后多余的字节, 同一个 socket) —— 多余的字节是帧的开头, 不能丢
async fn read_line_with_rest(mut socket: TcpStream, limit: usize) -> Result<(Vec<u8>, Vec<u8>, TcpStream)> {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 4096];
    loop {
        if let Some(position) = buffer.iter().position(|byte| *byte == b'\n') {
            let rest = buffer.split_off(position + 1);
            let mut line = buffer;
            line.truncate(position);
            return Ok((line, rest, socket));
        }
        if buffer.len() > limit {
            return Err(anyhow!("first line too long"));
        }
        let read = socket.read(&mut chunk).await?;
        if read == 0 {
            return Err(anyhow!("connection closed before first line"));
        }
        buffer.extend_from_slice(&chunk[..read]);
    }
}

async fn pump(socket: TcpStream, mut send: SendStream, mut recv: RecvStream, leftover: Vec<u8>, conn: &Connection) -> Result<(u64, u64)> {
    let (mut tcp_r, mut tcp_w) = socket.into_split();
    let up = async move {
        let mut total = leftover.len() as u64;
        if !leftover.is_empty() {
            send.write_all(&leftover).await?;
        }
        total += tokio::io::copy(&mut tcp_r, &mut send).await?;
        send.finish()?;
        Ok::<u64, anyhow::Error>(total)
    };
    let down = async move {
        let read = tokio::io::copy(&mut recv, &mut tcp_w).await?;
        let _ = tcp_w.shutdown().await;
        Ok::<u64, anyhow::Error>(read)
    };
    let result = tokio::try_join!(up, down);
    conn.close(0u32.into(), b"done");
    result
}

// ---- 入站 ----

async fn inbound_accept(state: Arc<State>) {
    loop {
        let incoming = match state.endpoint.accept().await {
            Some(incoming) => incoming,
            None => return,
        };
        let state = state.clone();
        tokio::spawn(async move {
            if let Err(error) = handle_inbound(incoming, state.clone()).await {
                eprintln!("inbound connection failed: {error:#}");
            }
        });
    }
}

async fn handle_inbound(incoming: iroh::endpoint::Incoming, state: Arc<State>) -> Result<()> {
    let conn = match tokio::time::timeout(Duration::from_millis(15_000), incoming.accept()?).await {
        Ok(Ok(conn)) => conn,
        Ok(Err(error)) => return Err(anyhow!("handshake failed: {error:#}")),
        Err(_) => return Err(anyhow!("handshake timed out")),
    };
    let peer = conn.remote_id().to_string();

    // 没有回调端口就不接听 —— JS 不在时连接留着没有意义
    if state.inbound_port == 0 {
        conn.close(0u32.into(), b"no inbound callback");
        return Ok(());
    }

    // 回调要在 accept_bi 之前: QUIC 的对端 open_bi 只有在写了第一个字节后才可见,
    // 而"对端拨进来"这件事在握手完成时就已经确定了 —— 先告诉 JS, 再等它写帧
    let callback = TcpStream::connect(("127.0.0.1", state.inbound_port)).await?;
    let (mut callback_r, mut callback_w) = callback.into_split();
    let line = json!({ "peer": peer }).to_string() + "\n";
    callback_w.write_all(line.as_bytes()).await?;

    state.active.fetch_add(1, Ordering::SeqCst);
    state.total.fetch_add(1, Ordering::SeqCst);
    state.last_busy_ms.store(now_ms(), Ordering::SeqCst);

    let (send, recv) = match tokio::time::timeout(Duration::from_millis(30_000), conn.accept_bi()).await {
        Ok(Ok(pair)) => pair,
        Ok(Err(error)) => {
            state.active.fetch_sub(1, Ordering::SeqCst);
            state.last_busy_ms.store(now_ms(), Ordering::SeqCst);
            return Err(anyhow!("accept_bi failed: {error:#}"));
        }
        Err(_) => {
            state.active.fetch_sub(1, Ordering::SeqCst);
            state.last_busy_ms.store(now_ms(), Ordering::SeqCst);
            conn.close(0u32.into(), b"no stream");
            return Err(anyhow!("peer opened no stream"));
        }
    };

    let (mut send, mut recv) = (send, recv);
    let up = async move {
        let moved = tokio::io::copy(&mut recv, &mut callback_w).await?;
        let _ = callback_w.shutdown().await;
        Ok::<u64, anyhow::Error>(moved)
    };
    let down = async move {
        let moved = tokio::io::copy(&mut callback_r, &mut send).await?;
        send.finish()?;
        Ok::<u64, anyhow::Error>(moved)
    };
    let result = tokio::try_join!(up, down);
    conn.close(0u32.into(), b"done");
    state.active.fetch_sub(1, Ordering::SeqCst);
    state.last_busy_ms.store(now_ms(), Ordering::SeqCst);
    let (peer_to_js, js_to_peer) = result?;
    println!("inbound connection done: peer={peer} peerToJs={peer_to_js} jsToPeer={js_to_peer}");
    Ok(())
}

async fn idle_watch(state: Arc<State>, mut rx: watch::Receiver<bool>) {
    if state.idle_ms == 0 {
        return;
    }
    let tick = Duration::from_millis(1_000).min(Duration::from_millis(state.idle_ms.max(1)));
    loop {
        tokio::select! {
            _ = tokio::time::sleep(tick) => {}
            _ = rx.changed() => return,
        }
        if state.active.load(Ordering::SeqCst) > 0 {
            state.last_busy_ms.store(now_ms(), Ordering::SeqCst);
            continue;
        }
        let idle_for = now_ms().saturating_sub(state.last_busy_ms.load(Ordering::SeqCst));
        if idle_for >= state.idle_ms {
            println!("idle for {idle_for}ms (limit {}ms), exiting", state.idle_ms);
            let _ = state.shutdown.send(true);
            return;
        }
    }
}

// ---- HTTP 解析 (控制面很小, 不引框架) ----

async fn read_request(socket: &mut TcpStream) -> Result<Request> {
    let mut buffer = Vec::new();
    let mut chunk = [0u8; 4096];
    let header_end = loop {
        let read = socket.read(&mut chunk).await?;
        if read == 0 {
            return Err(anyhow!("connection closed before headers"));
        }
        buffer.extend_from_slice(&chunk[..read]);
        if let Some(position) = find_subslice(&buffer, b"\r\n\r\n") {
            break position + 4;
        }
        if buffer.len() > 64 * 1024 {
            return Err(anyhow!("headers too large"));
        }
    };

    let head = String::from_utf8_lossy(&buffer[..header_end]).to_string();
    let mut lines = head.split("\r\n");
    let request_line = lines.next().ok_or_else(|| anyhow!("no request line"))?;
    let mut parts = request_line.split(' ');
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("/").to_string();

    let mut headers = HashMap::new();
    for line in lines {
        if let Some((name, value)) = line.split_once(':') {
            headers.insert(name.trim().to_ascii_lowercase(), value.trim().to_string());
        }
    }

    let content_length = headers.get("content-length").and_then(|value| value.parse::<usize>().ok()).unwrap_or(0);
    let mut body = buffer[header_end..].to_vec();
    while body.len() < content_length {
        let read = socket.read(&mut chunk).await?;
        if read == 0 {
            break;
        }
        body.extend_from_slice(&chunk[..read]);
    }
    body.truncate(content_length);

    let (path, query_text) = match target.split_once('?') {
        Some((path, query)) => (path.to_string(), query.to_string()),
        None => (target, String::new()),
    };
    let mut query = HashMap::new();
    for pair in query_text.split('&').filter(|value| !value.is_empty()) {
        match pair.split_once('=') {
            Some((key, value)) => {
                query.insert(key.to_string(), value.to_string());
            }
            None => {
                query.insert(pair.to_string(), String::new());
            }
        }
    }

    Ok(Request { method, path, query, headers, body })
}

async fn respond(socket: &mut TcpStream, status: u16, body: &str) -> Result<()> {
    let reason = match status {
        200 => "OK",
        400 => "Bad Request",
        401 => "Unauthorized",
        404 => "Not Found",
        502 => "Bad Gateway",
        _ => "OK",
    };
    let message = format!(
        "HTTP/1.1 {status} {reason}\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}",
        body.len()
    );
    socket.write_all(message.as_bytes()).await?;
    socket.flush().await?;
    Ok(())
}

fn find_subslice(haystack: &[u8], needle: &[u8]) -> Option<usize> {
    haystack.windows(needle.len()).position(|window| window == needle)
}

fn constant_time_eq(left: &str, right: &str) -> bool {
    if left.len() != right.len() {
        return false;
    }
    let mut diff = 0u8;
    for (a, b) in left.as_bytes().iter().zip(right.as_bytes()) {
        diff |= a ^ b;
    }
    diff == 0
}

// 旧的最小验证路径 (listen / dial) 保留, 便于不经过 HTTP 直接量
async fn listen(family: Option<bool>) -> Result<()> {
    let started = Instant::now();
    let endpoint = bind_endpoint().await?;
    endpoint.online().await;
    let mut addr = endpoint.addr();
    if let Some(v4_only) = family {
        let before = addr.addrs.len();
        addr.addrs.retain(|candidate| match candidate {
            TransportAddr::Ip(SocketAddr::V4(_)) => v4_only,
            TransportAddr::Ip(SocketAddr::V6(_)) => !v4_only,
            _ => true,
        });
        println!("listen: filtered to v4_only={v4_only}, addrs {before} -> {}", addr.addrs.len());
        for candidate in &addr.addrs {
            println!("listen: published {candidate}");
        }
    }
    let ticket = EndpointTicket::new(addr).to_string();
    println!("[{}ms] online, relay={:?}", started.elapsed().as_millis(), endpoint.addr().relay_urls().collect::<Vec<_>>());
    println!("TICKET {ticket}");
    let incoming = endpoint.accept().await.ok_or_else(|| anyhow!("endpoint closed"))?;
    let conn = incoming.accept()?.await?;
    let (path_kind, path_rtt_ms, path_remote) = describe_path(&conn);
    println!("[{}ms] connected via {path_kind} rtt={path_rtt_ms}ms remote={path_remote}", started.elapsed().as_millis());
    let (mut send, mut recv) = conn.accept_bi().await?;
    let recv_started = Instant::now();
    let data = recv.read_to_end(256 * 1024 * 1024).await?;
    let elapsed = recv_started.elapsed();
    println!(
        "[{}ms] received {} bytes in {} ms = {:.2} MiB/s",
        started.elapsed().as_millis(),
        data.len(),
        elapsed.as_millis(),
        (data.len() as f64 / 1048576.0) / elapsed.as_secs_f64()
    );
    send.write_all(b"ok").await?;
    send.finish()?;
    tokio::time::sleep(Duration::from_millis(1500)).await;
    endpoint.close().await;
    Ok(())
}

async fn dial(ticket_text: String, size: usize, family: Option<bool>) -> Result<()> {
    let started = Instant::now();
    let endpoint = bind_endpoint().await?;
    endpoint.online().await;
    let (connect_ms, send_ms, mibps, path_kind, _rtt, path_remote) = do_send(&endpoint, &ticket_text, size, family).await?;
    println!(
        "[{}ms] connect {connect_ms} ms via {path_kind} remote={path_remote}, sent {size} bytes in {send_ms} ms = {mibps:.2} MiB/s",
        started.elapsed().as_millis()
    );
    endpoint.close().await;
    Ok(())
}
