#!/usr/bin/env python3
"""
Controle da demonstração · Plataforma IoT Integrada

Abre uma página (http://localhost:8090) com botões para cada ato da apresentação: sobe e para os simuladores,
provoca congestionamento e falha de semáforo, derruba e restaura o link da borda, roda o adaptador, cria o domínio
novo, executa o teste de carga e mostra as provas numéricas. Também tem um botão que roda o roteiro inteiro sozinho.

Só usa a biblioteca padrão do Python. O sistema (central, borda, Docker) precisa estar rodando.

  python3 demo/controle.py
  python3 demo/controle.py --central http://localhost:8080 --borda http://localhost:8081 --porta 8090
"""
import argparse
import atexit
import base64
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
LOGS = RAIZ / "demo" / "logs"
CFG = {"central": "http://localhost:8080", "borda": "http://localhost:8081", "usuario": "admin", "senha": "admin123",
       "mqtt_host": "localhost", "mqtt_central": "1883", "mqtt_borda": "1884", "intervalo": "3", "fator": 1.0}

LOG = []                 # (hora, nivel, texto)
TRAVA = threading.Lock()
PROCS = {}               # nome -> Popen
PROC_TRAVA = threading.RLock()
UC4 = {"token": None}
ROTEIRO = {"rodando": False, "passo": "", "i": 0, "de": 0, "cancelar": threading.Event()}
_cache_servicos = {"quando": 0, "valor": {}}


def log(nivel, texto):
    with TRAVA:
        LOG.append((time.strftime("%H:%M:%S"), nivel, texto))
        del LOG[:-400]
    print(f"[{nivel}] {texto}", flush=True)


# ------------------------------------------------------------------ HTTP (cliente)
def chamar(metodo, url, corpo=None, auth=True, token=None, timeout=20):
    dados = json.dumps(corpo).encode() if corpo is not None else None
    rq = urllib.request.Request(url, data=dados, method=metodo)
    if corpo is not None:
        rq.add_header("Content-Type", "application/json")
    if auth:
        rq.add_header("Authorization", "Basic " + base64.b64encode(f"{CFG['usuario']}:{CFG['senha']}".encode()).decode())
    if token:
        rq.add_header("X-Device-Token", token)
    try:
        with urllib.request.urlopen(rq, timeout=timeout) as rs:
            texto = rs.read().decode() or "{}"
            status = rs.status
    except urllib.error.HTTPError as e:
        texto, status = e.read().decode() or "{}", e.code
    except Exception as e:  # sem conexão, tempo esgotado...
        return 0, {"erro": str(e)}
    try:
        return status, json.loads(texto)
    except ValueError:
        return status, {"texto": texto}


# ------------------------------------------------------------------ processos (simuladores)
def python_exe():
    venv = RAIZ / "simuladores" / "python" / ".venv" / "bin" / "python"
    return str(venv) if venv.exists() else sys.executable


def parar(nome):
    with PROC_TRAVA:
        p = PROCS.pop(nome, None)
    if p and p.poll() is None:
        try:
            os.killpg(os.getpgid(p.pid), signal.SIGTERM)
        except ProcessLookupError:
            pass
        return True
    return False


def vigiar(nome, p):
    time.sleep(4)
    if p.poll() is not None and p.returncode not in (0, None) and PROCS.get(nome) is p:
        try:
            cauda = (LOGS / f"{nome}.log").read_text(errors="replace").strip().splitlines()[-3:]
        except OSError:
            cauda = []
        log("erro", f"{nome} terminou com erro: " + " | ".join(cauda))


def iniciar(nome, cmd, cwd):
    with PROC_TRAVA:
        parar(nome)
        LOGS.mkdir(parents=True, exist_ok=True)
        saida = open(LOGS / f"{nome}.log", "w")
        try:
            p = subprocess.Popen(cmd, cwd=str(cwd), stdout=saida, stderr=subprocess.STDOUT, start_new_session=True)
        except FileNotFoundError as e:
            log("erro", f"Não encontrei o programa para iniciar {nome}: {e.filename}")
            return None
        PROCS[nome] = p
    threading.Thread(target=vigiar, args=(nome, p), daemon=True).start()
    return p


def parar_todos():
    for nome in list(PROCS):
        parar(nome)


atexit.register(parar_todos)


def sim_java(extra=()):
    return iniciar("java", ["java", "Simulador.java", "--destino", CFG["central"] + "/api/v1/ingestao", "--intervalo", CFG["intervalo"], *extra],
                   RAIZ / "simuladores" / "java")


def preparar_node():
    pasta = RAIZ / "simuladores" / "node"
    if (pasta / "node_modules").exists():
        return
    log("info", "Instalando as dependências do simulador Node.js (só na primeira vez)...")
    try:
        subprocess.run(["npm", "install", "--silent"], cwd=str(pasta), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=240)
        log("ok", "Dependências do Node.js instaladas")
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        log("erro", f"Não consegui instalar as dependências do Node.js: {e}")


def sim_node(extra=()):
    pasta = RAIZ / "simuladores" / "node"
    preparar_node()
    return iniciar("node", ["node", "simulador.js", "--broker", CFG["mqtt_host"], "--porta", CFG["mqtt_central"], "--intervalo", CFG["intervalo"], *extra], pasta)


def sim_python(extra=()):
    return iniciar("python", [python_exe(), "simulador.py", "--broker", CFG["mqtt_host"], "--porta", CFG["mqtt_borda"], "--intervalo", CFG["intervalo"], *extra],
                   RAIZ / "simuladores" / "python")


def psql(sql):
    try:
        r = subprocess.run(["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "iot", "-d", "iot", "-t", "-A", "-c", sql],
                           cwd=str(RAIZ), capture_output=True, text=True, timeout=60)
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        return None, str(e)
    return (r.stdout.strip() if r.returncode == 0 else None), (r.stderr.strip() or r.stdout.strip())


# ------------------------------------------------------------------ ações
def regra_id(nome):
    st, regras = chamar("GET", CFG["central"] + "/api/v1/regras")
    if st != 200 or not isinstance(regras, list):
        return None
    return next((r["id"] for r in regras if r["nome"] == nome), None)


def a_janela():
    rid = regra_id("Congestionamento")
    if rid is None:
        log("erro", "Não consegui ler as regras na central (ela está rodando? usuário e senha corretos?)")
        return
    st, _ = chamar("POST", f"{CFG['central']}/api/v1/regras/{rid}/janela?segundos=30")
    log("ok" if st == 200 else "erro", "Janela da regra de congestionamento ajustada para 30 s" if st == 200 else f"Falha ao ajustar a janela (HTTP {st})")


def a_trafego():
    a_janela()
    sim_java(); sim_node(); sim_python()
    log("ok", f"Simuladores iniciados (a cada {CFG['intervalo']} s): Java (HTTP, Centro), Node.js (MQTT, semáforos) e Python (MQTT, Periferia Norte via nó de borda)")


def a_parar():
    nomes = list(PROCS)
    parar_todos()
    log("info", "Simuladores parados" + (": " + ", ".join(nomes) if nomes else " (nenhum estava rodando)"))


def a_congestionar():
    a_janela()
    sim_java(["--congestionar", "cruz-brasil-rua10", "--congestionar-apos", "5", "--congestionar-por", "100"])
    log("alerta", "Congestionamento em Av. Brasil x Rua 10 por 100 s. O alarme abre em cerca de 45 s e se resolve sozinho no fim.")


def a_semaforo():
    sim_node(["--falha", "semaforo-brasil-rua10-01", "--falha-apos", "3", "--falha-por", "60"])
    log("alerta", "Falha no semáforo da Av. Brasil x Rua 10 por 60 s. O alarme crítico abre em poucos segundos.")


def a_link(ligado):
    st, r = chamar("POST", f"{CFG['borda']}/api/v1/borda/link?ligado={'true' if ligado else 'false'}", auth=False)
    if st == 200:
        log("alerta" if not ligado else "ok", ("Link do nó de borda DESLIGADO: a fila local começa a crescer" if not ligado
                                               else "Link do nó de borda RESTAURADO: a fila será enviada em lotes"))
    else:
        log("erro", f"Não consegui falar com o nó de borda em {CFG['borda']} ({r.get('erro', 'HTTP ' + str(st))})")


def a_adaptador():
    iniciar("adaptador", [python_exe(), "adaptador_fornecedor.py", "--demo", "5"], RAIZ / "adaptadores" / "python")
    log("info", "Adaptador de nível 2 rodando: o fornecedor envia JSON próprio e o adaptador traduz para o canônico (cruzamento da Rua 15)")


def a_invalida():
    st, r = chamar("POST", CFG["central"] + "/api/v1/ingestao", {"versao": "1.0"}, auth=False)
    log("alerta", f"Mensagem inválida enviada: a plataforma respondeu HTTP {st}")
    for e in (r.get("erros") or [])[:5]:
        log("info", "  motivo: " + str(e))
    st, dlq = chamar("GET", CFG["central"] + "/api/v1/mensagens-invalidas?size=1")
    if st == 200:
        log("ok", f"Fila de mensagens inválidas (DLQ): {dlq.get('total')} mensagem(ns) com o motivo registrado")


def a_uc4_criar():
    c = CFG["central"]
    passos = [
        ("domínio 'estacionamento'", "/api/v1/catalogo/dominios", {"slug": "estacionamento", "nome": "Estacionamento"}),
        ("perfil 'sensor-vaga'", "/api/v1/catalogo/perfis", {"dominio": "estacionamento", "slug": "sensor-vaga", "nome": "Sensor de vaga", "intervaloS": 120,
                                                              "grandezas": [{"grandeza": "vagas_livres", "unidade": "{vaga}", "min": 0, "max": 200}]}),
        ("regra 'Estacionamento lotado'", "/api/v1/regras", {"nome": "Estacionamento lotado", "dominio": "estacionamento", "grandeza": "vagas_livres",
                                                              "operador": "=", "limite": 0, "janelaS": 0, "severidade": "MEDIA"}),
        ("dispositivo 'vagas-praca-01'", "/api/v1/dispositivos", {"slug": "vagas-praca-01", "dominio": "estacionamento", "site": "centro", "area": "cruz-central-av2",
                                                                   "perfil": "sensor-vaga", "fabricante": "Simulado", "firmware": "1.0"}),
    ]
    for nome, caminho, corpo in passos:
        st, r = chamar("POST", c + caminho, corpo)
        if st in (200, 201):
            log("ok", f"Criado: {nome}")
            if "token" in r:
                UC4["token"] = r["token"]
        elif st == 409:
            log("alerta", f"Já existia: {nome}")
        else:
            log("erro", f"Falha ao criar {nome}: HTTP {st} {r}")
            return
    if not UC4["token"]:
        log("erro", "O dispositivo já existia e o token só aparece na criação. Use o botão 'Limpar o domínio de teste' e crie de novo.")
    else:
        log("ok", "Domínio novo pronto, sem alterar código nem reiniciar a central. Agora envie as leituras de vagas.")


def a_uc4_vaga(valor):
    if not UC4["token"]:
        log("erro", "Crie o domínio antes (botão 'Criar domínio Estacionamento')")
        return
    agora = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    msg = {"versao": "1.0", "id_mensagem": "vaga-" + uuid.uuid4().hex[:12], "dispositivo": "vagas-praca-01", "tipo": "telemetria", "timestamp": agora,
           "medidas": [{"grandeza": "vagas_livres", "valor": valor}]}
    st, r = chamar("POST", CFG["central"] + "/api/v1/ingestao", msg, auth=False, token=UC4["token"])
    extra = " -> alarme 'Estacionamento lotado' aberto" if valor == 0 and st == 202 else (" -> alarme resolvido sozinho" if valor > 0 and st == 202 else "")
    log("ok" if st == 202 else "erro", f"Sensor de vaga enviou {valor} vaga(s) livre(s): HTTP {st} {r.get('status', r)}{extra}")


def a_uc4_limpar():
    sql = ("DELETE FROM alarme WHERE chave IN (SELECT 'regra:' || id || ':vagas-praca-01' FROM regra WHERE nome = 'Estacionamento lotado'); "
           "DELETE FROM regra WHERE nome = 'Estacionamento lotado'; DELETE FROM dispositivo WHERE slug = 'vagas-praca-01'; "
           "DELETE FROM perfil WHERE slug = 'sensor-vaga'; DELETE FROM dominio WHERE slug = 'estacionamento'")
    ok, err = psql(sql)
    UC4["token"] = None
    log("ok" if ok is not None else "erro", "Domínio de teste removido" if ok is not None else f"Não consegui limpar (Docker?): {err}")


def a_carga():
    log("info", "Teste de carga: 20.000 mensagens em lotes de 1.000, 4 conexões paralelas...")
    try:
        r = subprocess.run([python_exe(), "simuladores/carga/carga.py", "--url", CFG["central"] + "/api/v1/ingestao/lote", "--total", "20000", "--lote", "1000", "--paralelo", "4"],
                           cwd=str(RAIZ), capture_output=True, text=True, timeout=180)
        for linha in (r.stdout + r.stderr).strip().splitlines()[-3:]:
            log("ok" if "mensagens/s" in linha else "info", linha)
    except subprocess.TimeoutExpired:
        log("erro", "Teste de carga excedeu 180 s")


def a_limpar_dados():
    ok, err = psql("TRUNCATE telemetria, ultimo_estado, alarme, mensagem_processada, mensagem_invalida")
    log("ok" if ok is not None else "erro", "Dados de demonstração zerados (catálogo e regras mantidos)" if ok is not None
        else f"Não consegui limpar (Docker?): {err}")
    log("info", "Obs.: a fila do nó de borda não é zerada por aqui. Para zerar, pare o nó e apague no-borda/data/borda.db*")


def a_prova():
    st, b = chamar("GET", CFG["borda"] + "/api/v1/borda/status", auth=False)
    if st != 200:
        log("erro", "Não consegui ler o estado do nó de borda")
        return
    log("info", f"Nó de borda: link {'ligado' if b.get('linkLigado') else 'desligado'}, fila {b.get('filaPendente')}, enviadas {b.get('enviadas')}")
    gravadas, err = psql("SELECT count(DISTINCT id_mensagem) FROM telemetria WHERE via = 'borda-periferia-norte'")
    if gravadas is None:
        log("erro", f"Não consegui contar no banco (Docker?): {err}")
        return
    if str(b.get("enviadas")) == gravadas and b.get("filaPendente") == 0:
        log("ok", f"PROVA: enviadas pelo nó = {b.get('enviadas')} = gravadas pela central = {gravadas} -> sem perda e sem duplicação")
    else:
        log("alerta", f"enviadas = {b.get('enviadas')}, gravadas = {gravadas}, fila = {b.get('filaPendente')}. Pare o simulador Python, espere 10 s e rode de novo.")


def a_checar():
    s = servicos(forcar=True)
    log("ok" if s["central"] else "erro", "Central: " + ("no ar" if s["central"] else f"NÃO responde em {CFG['central']}"))
    log("ok" if s["borda"] else "erro", "Nó de borda: " + ("no ar" if s["borda"] else f"NÃO responde em {CFG['borda']}"))
    log("info", "Simuladores em execução: " + (", ".join(n for n, p in PROCS.items() if p.poll() is None) or "nenhum"))


ACOES = {
    "checar": a_checar, "janela": a_janela, "trafego": a_trafego, "parar": a_parar, "congestionar": a_congestionar, "semaforo": a_semaforo,
    "derrubar": lambda: a_link(False), "restaurar": lambda: a_link(True), "adaptador": a_adaptador, "invalida": a_invalida,
    "uc4_criar": a_uc4_criar, "uc4_lotado": lambda: a_uc4_vaga(0), "uc4_livre": lambda: a_uc4_vaga(5), "uc4_limpar": a_uc4_limpar,
    "carga": a_carga, "limpar": a_limpar_dados, "prova": a_prova,
}


# ------------------------------------------------------------------ roteiro automático
def parar_python():
    if parar("python"):
        log("info", "Simulador Python parado para a contagem final")


ROTEIRO_PASSOS = [
    ("Preparar: janela da regra em 30 s", a_janela, 1),
    ("Ato 1: iniciar os três simuladores", a_trafego, 20),
    ("Ato 2: congestionamento e falha de semáforo", lambda: (a_congestionar(), a_semaforo()), 80),
    ("Ato 3: derrubar o link da borda", lambda: a_link(False), 70),
    ("Ato 3: restaurar o link da borda", lambda: a_link(True), 20),
    ("Ato 4: adaptador de formato próprio", a_adaptador, 14),
    ("Ato 4: mensagem inválida para a DLQ", a_invalida, 3),
    ("Ato 5: criar o domínio Estacionamento", a_uc4_criar, 3),
    ("Ato 5: vagas livres = 12", lambda: a_uc4_vaga(12), 3),
    ("Ato 5: vagas livres = 0 (abre alarme)", lambda: a_uc4_vaga(0), 8),
    ("Ato 5: vagas livres = 5 (resolve o alarme)", lambda: a_uc4_vaga(5), 3),
    ("Ato 6: teste de carga", a_carga, 2),
    ("Prova: parar o simulador Python", parar_python, 12),
    ("Prova: enviadas pela borda x gravadas pela central", a_prova, 1),
]


def rodar_roteiro():
    if ROTEIRO["rodando"]:
        return
    ROTEIRO.update(rodando=True, de=len(ROTEIRO_PASSOS), i=0, passo="")
    ROTEIRO["cancelar"].clear()
    log("info", "=== ROTEIRO AUTOMÁTICO INICIADO ===")
    for i, (nome, fn, espera) in enumerate(ROTEIRO_PASSOS, start=1):
        if ROTEIRO["cancelar"].is_set():
            break
        ROTEIRO.update(i=i, passo=nome)
        log("info", f"[{i}/{len(ROTEIRO_PASSOS)}] {nome}")
        try:
            fn()
        except Exception as e:  # um passo com problema não derruba o roteiro
            log("erro", f"Passo falhou: {e}")
        for _ in range(max(1, int(espera / CFG["fator"] * 2))):
            if ROTEIRO["cancelar"].wait(0.5):
                break
    log("alerta" if ROTEIRO["cancelar"].is_set() else "ok", "=== ROTEIRO CANCELADO ===" if ROTEIRO["cancelar"].is_set() else "=== ROTEIRO CONCLUÍDO ===")
    ROTEIRO.update(rodando=False, passo="")


def servicos(forcar=False):
    if not forcar and time.time() - _cache_servicos["quando"] < 3:
        return _cache_servicos["valor"]
    c, _ = chamar("GET", CFG["central"] + "/actuator/health", auth=False, timeout=2)
    b, _ = chamar("GET", CFG["borda"] + "/api/v1/borda/status", auth=False, timeout=2)
    _cache_servicos.update(quando=time.time(), valor={"central": c == 200, "borda": b == 200})
    return _cache_servicos["valor"]


# ------------------------------------------------------------------ servidor da página
class Manipulador(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def _json(self, obj, status=200):
        corpo = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(corpo)))
        self.end_headers()
        self.wfile.write(corpo)

    def do_GET(self):
        if self.path.startswith("/estado"):
            try:
                desde = int(self.path.split("desde=")[1])
            except (IndexError, ValueError):
                desde = 0
            with TRAVA:
                novos = LOG[desde:]
                total = len(LOG)
            return self._json({"logs": novos, "total": total, "servicos": servicos(),
                               "procs": {n: (p.poll() is None) for n, p in PROCS.items()}, "uc4": bool(UC4["token"]),
                               "roteiro": {k: ROTEIRO[k] for k in ("rodando", "passo", "i", "de")}, "central": CFG["central"]})
        pagina = (Path(__file__).parent / "controle.html").read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(pagina)))
        self.end_headers()
        self.wfile.write(pagina)

    def do_POST(self):
        nome = self.path.rsplit("/", 1)[-1]
        if nome == "roteiro":
            threading.Thread(target=rodar_roteiro, daemon=True).start()
            return self._json({"ok": True})
        if nome == "cancelar":
            ROTEIRO["cancelar"].set()
            return self._json({"ok": True})
        acao = ACOES.get(nome)
        if not acao:
            return self._json({"erro": "ação desconhecida"}, 404)
        threading.Thread(target=acao, daemon=True).start()
        self._json({"ok": True})


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--central", default=CFG["central"])
    ap.add_argument("--borda", default=CFG["borda"])
    ap.add_argument("--usuario", default=CFG["usuario"])
    ap.add_argument("--senha", default=CFG["senha"])
    ap.add_argument("--porta", type=int, default=8090, help="porta desta página de controle")
    ap.add_argument("--mqtt-host", default=CFG["mqtt_host"])
    ap.add_argument("--mqtt-central", default=CFG["mqtt_central"])
    ap.add_argument("--mqtt-borda", default=CFG["mqtt_borda"])
    ap.add_argument("--intervalo", default=CFG["intervalo"], help="segundos entre leituras dos simuladores")
    ap.add_argument("--fator", type=float, default=1.0, help="acelera as esperas do roteiro automático (ex.: 2 = metade do tempo)")
    a = ap.parse_args()
    CFG.update(central=a.central.rstrip("/"), borda=a.borda.rstrip("/"), usuario=a.usuario, senha=a.senha,
               mqtt_host=a.mqtt_host, mqtt_central=a.mqtt_central, mqtt_borda=a.mqtt_borda, intervalo=a.intervalo, fator=max(a.fator, 0.1))
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    threading.Thread(target=preparar_node, daemon=True).start()
    srv = ThreadingHTTPServer(("127.0.0.1", a.porta), Manipulador)
    log("info", f"Controle da demonstração em http://localhost:{a.porta}  (central {CFG['central']}, borda {CFG['borda']})")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        parar_todos()


if __name__ == "__main__":
    main()
