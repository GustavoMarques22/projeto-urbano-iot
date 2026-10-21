#!/usr/bin/env python3
"""
Teste de carga da ingestão em lote (RNF-01/RNF-02): envia N mensagens em lotes ao endpoint
POST /api/v1/ingestao/lote, usando o token do nó de borda de demonstração, e mede mensagens por segundo.
Só usa a biblioteca padrão do Python.

  python carga.py --total 20000 --lote 1000 --paralelo 4
"""
import argparse
import json
import time
import urllib.request
import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

DISPOSITIVOS = ["contador-brasil-rua10-01", "contador-brasil-rua15-01", "contador-central-av2-01",
                "contador-norte-1-01", "contador-norte-2-01"]


def lote(n, i0):
    agora = datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    return [{
        "versao": "1.0", "id_mensagem": str(uuid.uuid4()), "dispositivo": DISPOSITIVOS[(i0 + k) % len(DISPOSITIVOS)],
        "tipo": "telemetria", "timestamp": agora,
        "medidas": [{"grandeza": "fluxo_veiculos", "valor": 40.0}, {"grandeza": "velocidade_media", "valor": 35.0},
                    {"grandeza": "ocupacao_via", "valor": 30.0}],
    } for k in range(n)]


def enviar(url, no, token, n, i0):
    rq = urllib.request.Request(url, data=json.dumps(lote(n, i0)).encode(), method="POST", headers={
        "Content-Type": "application/json", "X-Node-Id": no, "X-Node-Token": token})
    with urllib.request.urlopen(rq, timeout=120) as rs:
        r = json.loads(rs.read())
    return r["aceitas"], r["recusadas"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", default="http://localhost:8080/api/v1/ingestao/lote")
    ap.add_argument("--no", default="borda-periferia-norte")
    ap.add_argument("--token", default="borda-demo-token")
    ap.add_argument("--total", type=int, default=10000)
    ap.add_argument("--lote", type=int, default=500)
    ap.add_argument("--paralelo", type=int, default=4)
    a = ap.parse_args()

    lotes = [(a.lote, i * a.lote) for i in range(a.total // a.lote)]
    ini = time.time()
    aceitas = recusadas = 0
    with ThreadPoolExecutor(max_workers=a.paralelo) as ex:
        for ok, ruim in ex.map(lambda p: enviar(a.url, a.no, a.token, p[0], p[1]), lotes):
            aceitas += ok
            recusadas += ruim
    dur = time.time() - ini
    print(f"{aceitas} aceitas, {recusadas} recusadas em {dur:.1f}s => {aceitas / dur:.0f} mensagens/s")
    print("Meta do RNF-01: 1.000 mensagens/s")


if __name__ == "__main__":
    main()
