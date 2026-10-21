#!/usr/bin/env python3
"""
Adaptador de protocolo (integração de nível 2) - Adapter pattern.

Um contador de um "Fornecedor X" publica um JSON PRÓPRIO, que não pode ser alterado:
    topico: fornecedor-x/contagem/FX-0007
    corpo : {"sensor": "FX-0007", "hora": 1760979900, "veiculos_min": 31, "vel_kmh": 22.4, "ocupacao": 58}

Este adaptador assina o tópico do fornecedor, traduz para a mensagem canônica e publica no tópico canônico.
Pode rodar como contêiner separado, sem recompilar nem reiniciar a plataforma (RF-INT-03).

Uso:
  python adaptador_fornecedor.py                 # fica traduzindo mensagens do fornecedor
  python adaptador_fornecedor.py --demo 10       # também simula o fornecedor, enviando 10 mensagens legadas
"""
import argparse
import json
import time
import uuid
from datetime import datetime, timezone

import paho.mqtt.client as mqtt

# de-para: sensor do fornecedor -> dispositivo cadastrado na plataforma
MAPA = {"FX-0007": {"dispositivo": "contador-brasil-rua15-01", "site": "centro", "area": "cruz-brasil-rua15"}}
INQUILINO, DOMINIO = "semob", "mobilidade"


def criar_cliente(nome):
    try:
        return mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id=nome)
    except AttributeError:
        return mqtt.Client(client_id=nome)


def traduzir(legado):
    destino = MAPA.get(legado.get("sensor"))
    if destino is None:
        raise ValueError(f"Sensor desconhecido: {legado.get('sensor')}")
    ts = datetime.fromtimestamp(legado["hora"], tz=timezone.utc).isoformat(timespec="seconds").replace("+00:00", "Z")
    return destino, {
        "versao": "1.0",
        "id_mensagem": str(uuid.uuid5(uuid.NAMESPACE_URL, f"{legado['sensor']}/{legado['hora']}")),  # estável: reenvio não duplica
        "dispositivo": destino["dispositivo"],
        "tipo": "telemetria",
        "timestamp": ts,
        "local": {"site": destino["site"], "area": destino["area"]},
        "medidas": [
            {"grandeza": "fluxo_veiculos", "valor": legado["veiculos_min"], "unidade": "{veic}/min"},
            {"grandeza": "velocidade_media", "valor": legado["vel_kmh"], "unidade": "km/h"},
            {"grandeza": "ocupacao_via", "valor": legado["ocupacao"], "unidade": "%"},
        ],
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--broker", default="localhost")
    ap.add_argument("--porta", type=int, default=1883)
    ap.add_argument("--demo", type=int, default=0, help="simula N mensagens do fornecedor")
    args = ap.parse_args()

    cliente = criar_cliente("adaptador-fornecedor-x")

    def ao_conectar(c, *_):
        c.subscribe("fornecedor-x/contagem/#", qos=1)
        print("Adaptador pronto: traduzindo fornecedor-x/contagem/# para o formato canônico")

    def ao_receber(c, _u, m):
        try:
            destino, canonica = traduzir(json.loads(m.payload.decode("utf-8")))
            topico = f"{INQUILINO}/{destino['site']}/{DOMINIO}/{destino['dispositivo']}/telemetria"
            c.publish(topico, json.dumps(canonica), qos=1)
            print(f"traduzida: {m.topic} -> {topico}")
        except Exception as e:  # mensagem do fornecedor fora do padrão
            print(f"descartada ({e}): {m.payload[:120]!r}")

    cliente.on_connect = ao_conectar
    cliente.on_message = ao_receber
    cliente.connect(args.broker, args.porta, 60)
    cliente.loop_start()

    try:
        if args.demo:
            time.sleep(1)
            for i in range(args.demo):
                legado = {"sensor": "FX-0007", "hora": int(time.time()), "veiculos_min": 30 + i, "vel_kmh": 22.4, "ocupacao": 58}
                cliente.publish("fornecedor-x/contagem/FX-0007", json.dumps(legado), qos=1)
                time.sleep(2)
        else:
            while True:
                time.sleep(1)
    except KeyboardInterrupt:
        pass
    finally:
        time.sleep(1)
        cliente.loop_stop()
        cliente.disconnect()


if __name__ == "__main__":
    main()
