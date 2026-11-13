# 🚗 Qonecta Unit (`v0.1.18`)

<p align="center">
  <b>Idioma / Language / Sprache / Idioma:</b><br/>
  <a href="README.md">🇪🇸 <b>Español</b></a> ·
  <a href="README.en.md">🇬🇧 <b>English</b></a> ·
  <a href="README.de.md">🇩🇪 <b>Deutsch</b></a> ·
  🇵🇹 <b>Português</b>
</p>

<p align="center">
  <b>por <a href="https://github.com/CharlysEV">CharlysEV</a></b> · Android Auto, CarPlay, Painel Estendido e Planeador EV nativo no ecrã do <b>Leapmotor C10</b>
</p>

<p align="center">
  <a href="https://github.com/CharlysEV/qonecta-unit/releases/latest"><b>⬇️ Transferir Qonecta Unit (Carro)</b></a> ·
  <a href="https://github.com/CharlysEV/qonecta-unit-companion/releases/latest"><b>📱 Transferir Qonecta Companion (Telemóvel)</b></a>
</p>

<p align="center">
  <b>Manuais passo a passo:</b>
  <a href="docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md">🇵🇹 Português</a> ·
  <a href="docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md">🇪🇸 Español</a> ·
  <a href="docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md">🇬🇧 English</a> ·
  <a href="docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md">🇩🇪 Deutsch</a>
</p>

---

**Qonecta Unit** é a aplicação gratuita e de código aberto instalada **diretamente no sistema multimédia original do Leapmotor C10** (tanto BEV como REEV). Transforma o ecrã nativo do veículo num recetor completo de **Android Auto** (sem fios ou por cabo USB) e **Apple CarPlay**, acompanhado por um **Painel Estendido C10** com telemetria em tempo real, histórico automático de viagens, cálculo de custos elétricos/combustível e **Planeador de Rotas EV com paragens de carregamento**.

Funciona sincronizado em tempo real com o **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion)** no seu telemóvel Android (disponível em **Português, Espanhol, Inglês e Alemão**), permitindo inclusive **ligar unicamente por Bluetooth (sem ligar o Wi‑Fi 5 GHz)** e respeitando a 100 % o **Rádio FM/DAB original** e os comandos do volante.

---

## ✨ Principais novidades

- **☑️ Caixas de ligação independentes (`☑ / ☐`) e Modo Só Bluetooth real:**
  Marque ou desmarque livremente **`Bluetooth`**, **`Wi-Fi 5 GHz`**, **`Hotspot Móvel`** e **`Cabo USB`** tanto no carro como no telemóvel. Se deixar marcado apenas **`☑ Bluetooth`**, nem o carro nem o telemóvel levantam redes Wi‑Fi 5 GHz nem despertam o Android Auto sem fios: sincroniza viagens, tarifas, rotas multi-paragem do Google Maps e Internet ligeira por Bluetooth puro, podendo usar a porta USB apenas para carregar a bateria.
- **📻 Convivência 100 % limpa com o Rádio FM/DAB e os comandos do volante:**
  Gestão de foco de áudio transitório (`AUDIOFOCUS_GAIN_TRANSIENT`) com libertação garantida em pausa/paragem/erro e filtragem rigorosa das teclas do volante para que o rádio nativo do C10 nunca fique mudo nem bloqueado.
- **🔄 Sincronização instantânea `Qonecta Unit ↔ Qonecta Companion`:**
  As opções marcadas no ecrã do carro e o estado do emparelhamento são mostrados em tempo real no telemóvel.
- **🎓 Modo Tutorial interativo no `Qonecta Companion` (5 passos em direto em PT/ES/EN/DE):**
  Assistente passo a passo para a primeira instalação no C10 que verifica/transfere automaticamente o APK do carro, confirma a gravação na Pendrive USB‑C, deteta quando liga o Hotspot (zero Proxy e zero DNS) e inclui um **radar HTTP em direto** que fica verde no milissegundo exato em que o navegador **Vivaldi** do carro abre o portal local — com esquema visual do botão **`⤢`** na app *Ficheiros*.
- **⚡ Atualizações OTA num único toque:**
  Concluída a primeira instalação, todas as versões futuras são enviadas diretamente do telemóvel para o ecrã do carro num só toque (com verificação `SHA-256`), sem voltar a precisar de pendrive nem de navegador.

---

## 🧩 As duas ferramentas do ecossistema

| Ferramenta | Onde se instala | Transferência oficial | O que faz |
| :--- | :--- | :--- | :--- |
| **🚗 Qonecta Unit** | Ecrã do **Leapmotor C10** | [Releases · `qonecta-unit`](https://github.com/CharlysEV/qonecta-unit/releases/latest) | Android Auto / CarPlay, Painel Estendido C10, Viagens, Consumos BEV/REEV e Planeador EV com paragens |
| **📱 Qonecta Companion** | O seu **telemóvel Android** | [Releases · `qonecta-unit-companion`](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) | App em PT/ES/EN/DE com Modo Tutorial C10 em direto, ponte automática para o Google Maps, Internet por Bluetooth, tarifas/viagens e atualização num toque |

> 💡 **Qual a diferença em relação ao [Qonecta+](https://github.com/CharlysEV/qonecta)?**
> O `Qonecta+` corre no telemóvel projetando via *QDLink/SSPLink* sem instalar nada no carro. Já o **`Qonecta Unit` corre de forma nativa dentro do hardware do C10**, acompanhado pelo **`Qonecta Companion`** no telemóvel. Se já tinha o `Qonecta+` instalado no telefone, desative o seu arranque automático enquanto usar o `Qonecta Unit` para não competirem pelo Android Auto em simultâneo.

---

## 🚀 Primeira instalação rápida em 3 minutos

1. Instale o **[Qonecta Companion](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest)** no seu telemóvel Android e transfira o navegador **Vivaldi** na loja oficial `LeapAppStore` do seu Leapmotor C10.
2. Abra o **Qonecta Companion** (já em Português ou escolhendo **`🇵🇹 PT`** no topo) e toque em **`🎓 Abrir Modo Tutorial passo a passo (C10)`**: o assistente transfere o APK do carro, guia-o para o guardar numa pendrive USB‑C, deteta o seu Hotspot e abre os *Ficheiros* do carro a partir do Vivaldi para instalar tocando no ícone de canto **`⤢`**.
3. Consulte o manual completo no seu idioma:
   - 🇵🇹 **Português:** [docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md](docs/MANUAL_INSTALACAO_QONECTA_UNIT_C10.pt.md)
   - 🇪🇸 **Español:** [docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md](docs/MANUAL_INSTALACION_QONECTA_UNIT_C10.md)
   - 🇬🇧 **English:** [docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md](docs/MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md)
   - 🇩🇪 **Deutsch:** [docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md](docs/INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md)

---

## ☕ Apoiar o projeto

O **Qonecta Unit**, o **Qonecta Companion** e o **Qonecta+** são projetos abertos, gratuitos e sem anúncios criados com dedicação para a comunidade Leapmotor. Se lhe são úteis no dia a dia e quiser oferecer um café ou apoiar o desenvolvimento:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Muito obrigado pelo apoio!

---

## 🛠️ Compilação a partir do código-fonte

Requer **JDK 17**, **Android SDK 36**, **NDK `29.0.14206865`** e **CMake `3.22.1`**.

```sh
./gradlew :bridge:testDebugUnitTest :app:testGithubDebugUnitTest :app:assembleGithubCoche :companion:assembleRelease
```

> Nunca envie chaves privadas (`*.keystore`, `*.jks`, `key.properties`, `firma/`) para o repositório. Os APKs oficiais publicados no GitHub Releases são assinados com a chave privada do autor para permitir atualizações num toque sem perder dados.

---

## 📜 Créditos e Licença

Desenvolvido por **[CharlysEV](https://github.com/CharlysEV)** como adaptação independente para o **Leapmotor C10**, a partir do [DiAuto](https://github.com/shihabal3amri/DiAuto) (README original em [README_DIAUTO.md](README_DIAUTO.md)) e [Open Headunit](https://github.com/andreknieriem/open-headunit) / [Michael Reid](https://github.com/mikereidis/headunit).

Distribuído sob a licença **[GNU AGPL-3.0](LICENSE)**. Consulte [LICENSE](LICENSE) e [docs/THIRD_PARTY.md](docs/THIRD_PARTY.md). *Android Auto* é uma marca registada da Google LLC. Projeto comunitário independente, não afiliado nem endossado pela Leapmotor ou pela Google.
