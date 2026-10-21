#!/usr/bin/env python3
"""
Simulador de contadores de veículos (Python, via MQTT) - integração de nível 1 (nativa).

Publica no formato canônico nos tópicos {inquilino}/{site}/{dominio}/{dispositivo}/telemetria.
Por padrão simula a Periferia Norte e publica no broker do NÓ DE BORDA (porta 1884).

Exemplos:
  python simulador.py                                   # periferia norte -> borda (localhost:1884)
  python simulador.py --porta 1883 --site centro        # centro -> broker da central
  python simulador.py --congestionar cruz-norte-1 --congestionar-apos 20 --congestionar-por 90
"""
import argparse
import json
import random
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

INQUILINO, DOMINIO = "semob", "mobilidade"
# site -> [(area, dispositivo)]
CONTADORES = {
    "centro": [("cruz-brasil-rua10", "contador-brasil-rua10-01"),
               ("cruz-central-av2", "contador-central-av2-01")],
    "periferia-norte": [("cruz-norte-1", "contador-norte-1-01"),
                        ("cruz-norte-2", "contador-norte-2-01")],
}


def criar_cliente():
    try:  # paho-mqtt 2.x
        return mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="sim-py-" + uuid.uuid4().hex[:6])
    except AttributeError:  # paho-mqtt 1.x
        return mqtt.Client(client_id="sim-py-" + uuid.uuid4().hex[:6])


def medidas(congestionado):
    if congestionado:
        vel, fluxo, ocup = random.gauss(8, 2.5), random.gauss(20, 4), random.gauss(86, 4)
    else:
        vel, fluxo, ocup = random.gauss(38, 5), random.gauss(42, 8), random.gauss(32, 7)
    return [
        {"grandeza": "fluxo_veiculos", "valor": round(max(fluxo, 0), 1), "unidade": "{veic}/min"},
        {"grandeza": "velocidade_media", "valor": round(max(vel, 1), 1), "unidade": "km/h"},
        {"grandeza": "ocupacao_via", "valor": round(min(max(ocup, 0), 100), 1), "unidade": "%"},
    ]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--broker", default="localhost")
    ap.add_argument("--porta", type=int, default=1884, help="1884 = broker do nó de borda; 1883 = broker da central")
    ap.add_argument("--site", choices=list(CONTADORES) + ["todos"], default="periferia-norte")
    ap.add_argument("--intervalo", type=float, default=5, help="segundos entre leituras")
    ap.add_argument("--duracao", type=float, default=0, help="segundos totais (0 = até Ctrl+C)")
    ap.add_argument("--congestionar", action="append", default=[], metavar="AREA",
                    help="área que ficará congestionada (pode repetir)")
    ap.add_argument("--congestionar-apos", type=float, default=0, help="segundos até iniciar o congestionamento")
    ap.add_argument("--congestionar-por", type=float, default=120, help="duração do congestionamento em segundos")
    args = ap.parse_args()

    sites = list(CONTADORES) if args.site == "todos" else [args.site]
    cliente = criar_cliente()
    cliente.connect(args.broker, args.porta, 60)
    cliente.loop_start()
    inicio = time.time()
    enviadas = 0
    print(f"Simulando {sites} em {args.broker}:{args.porta} a cada {args.intervalo}s (Ctrl+C para parar)")
    try:
        while args.duracao == 0 or time.time() - inicio < args.duracao:
            decorrido = time.time() - inicio
            em_congestionamento = args.congestionar_apos <= decorrido < args.congestionar_apos + args.congestionar_por
            for site in sites:
                for area, dispositivo in CONTADORES[site]:
                    cong = em_congestionamento and area in args.congestionar
                    msg = {
                        "versao": "1.0",
                        "id_mensagem": str(uuid.uuid4()),
                        "dispositivo": dispositivo,
                        "tipo": "telemetria",
                        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                        "local": {"site": site, "area": area},
                        "medidas": medidas(cong),
                    }
                    topico = f"{INQUILINO}/{site}/{DOMINIO}/{dispositivo}/telemetria"
                    cliente.publish(topico, json.dumps(msg), qos=1)
                    enviadas += 1
            if enviadas % 10 == 0:
                estado = "CONGESTIONADO" if (em_congestionamento and args.congestionar) else "normal"
                print(f"{enviadas} mensagens enviadas ({estado})")
            time.sleep(args.intervalo)
    except KeyboardInterrupt:
        pass
    finally:
        cliente.loop_stop()
        cliente.disconnect()
        print(f"Encerrado: {enviadas} mensagens enviadas")


if __name__ == "__main__":
    main()
