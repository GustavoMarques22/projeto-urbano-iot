#!/usr/bin/env node
/*
 * Simulador de semáforos (Node.js, via MQTT) - integração de nível 1 (nativa).
 * Publica fase_atual (0=vermelho, 1=amarelo, 2=verde), tempo_ciclo e falha no formato canônico.
 *
 * Exemplos:
 *   node simulador.js
 *   node simulador.js --falha semaforo-brasil-rua10-01 --falha-apos 20 --falha-por 60
 */
const mqtt = require('mqtt');
const crypto = require('crypto');

const INQUILINO = 'semob';
const DOMINIO = 'mobilidade';
const SEMAFOROS = [
  { site: 'centro', area: 'cruz-brasil-rua10', dispositivo: 'semaforo-brasil-rua10-01' },
  { site: 'centro', area: 'cruz-brasil-rua15', dispositivo: 'semaforo-brasil-rua15-01' },
  { site: 'centro', area: 'cruz-central-av2', dispositivo: 'semaforo-central-av2-01' },
];

function argumento(nome, padrao) {
  const i = process.argv.indexOf('--' + nome);
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : padrao;
}

const broker = argumento('broker', 'localhost');
const porta = argumento('porta', '1883');
const intervaloMs = Number(argumento('intervalo', '5')) * 1000;
const falhaDispositivo = argumento('falha', '');
const falhaApos = Number(argumento('falha-apos', '0'));
const falhaPor = Number(argumento('falha-por', '60'));

const cliente = mqtt.connect(`mqtt://${broker}:${porta}`);
const inicio = Date.now();
let ciclo = 0;

cliente.on('connect', () => console.log(`Semáforos simulados em ${broker}:${porta} a cada ${intervaloMs / 1000}s (Ctrl+C para parar)`));
cliente.on('error', (e) => console.error('Erro MQTT:', e.message));

setInterval(() => {
  if (!cliente.connected) { return; }
  ciclo += 1;
  const decorrido = (Date.now() - inicio) / 1000;
  SEMAFOROS.forEach((s, i) => {
    const emFalha = s.dispositivo === falhaDispositivo && decorrido >= falhaApos && decorrido < falhaApos + falhaPor;
    const fase = (ciclo + i) % 3; // 0 vermelho, 1 amarelo, 2 verde
    const msg = {
      versao: '1.0',
      id_mensagem: crypto.randomUUID(),
      dispositivo: s.dispositivo,
      tipo: 'telemetria',
      timestamp: new Date().toISOString(),
      local: { site: s.site, area: s.area },
      medidas: [
        { grandeza: 'fase_atual', valor: fase, unidade: '1' },
        { grandeza: 'tempo_ciclo', valor: 90, unidade: 's' },
        { grandeza: 'falha', valor: emFalha ? 1 : 0, unidade: '1' },
      ],
    };
    cliente.publish(`${INQUILINO}/${s.site}/${DOMINIO}/${s.dispositivo}/telemetria`, JSON.stringify(msg), { qos: 1 });
  });
  if (ciclo % 5 === 0) { console.log(`${ciclo * SEMAFOROS.length} mensagens enviadas`); }
}, intervaloMs);

process.on('SIGINT', () => { cliente.end(); process.exit(0); });
