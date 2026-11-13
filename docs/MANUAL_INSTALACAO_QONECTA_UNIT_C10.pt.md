# 🚗📱 Manual Passo a Passo · Qonecta Unit & Qonecta Companion (`v0.1.19`)

<p align="center">
  <a href="MANUAL_INSTALACION_QONECTA_UNIT_C10.md">🇪🇸 Español</a> ·
  <a href="MANUAL_INSTALLATION_QONECTA_UNIT_C10.en.md">🇬🇧 English</a> ·
  <a href="INSTALLATIONSANLEITUNG_QONECTA_UNIT_C10.de.md">🇩🇪 Deutsch</a> ·
  🇵🇹 <b>Português</b>
</p>

Guia claro passo a passo para instalar, emparelhar e atualizar o **Qonecta Unit** (a aplicação instalada diretamente no ecrã do **Leapmotor C10**) e o **Qonecta Companion** (a aplicação complementar no seu **telemóvel Android**), criadas por **[CharlysEV](https://github.com/CharlysEV)**.

> **⚠️ Segurança em primeiro lugar:** Realize sempre a instalação e o primeiro emparelhamento com o veículo estacionado em **`P`**.

---

## 🧩 O que são estas duas ferramentas?

1. **🚗 `Qonecta Unit` (`QonectaUnit_0.1.18-unit.apk` · Instala-se NO CARRO):**
   Transforma o ecrã original do Leapmotor C10 num recetor nativo de **Android Auto** (sem fios ou por cabo USB) e **Apple CarPlay**, com **Painel Estendido C10** (bateria, autonomia, potência, histórico de viagens, cálculo de consumos e custos BEV/REEV e **Planeador de Rotas EV com paragens de carregamento**), caixas de seleção independentes (**Modo Só Bluetooth sem Wi‑Fi 5 GHz**) e convivência 100 % limpa com o Rádio FM/DAB original e os comandos do volante.
2. **📱 `Qonecta Companion` (`QonectaUnitCompanion_0.1.19.apk` · Instala-se NO SEU TELEMÓVEL ANDROID):**
   Disponível em **4 idiomas (`🇪🇸 Español · 🇬🇧 English · 🇩🇪 Deutsch · 🇵🇹 Português`)** com deteção automática ou seleção manual imediata no topo da app. Sincroniza em tempo real com o `Qonecta Unit` assim que entra no carro para:
   - Guiá-lo na primeira instalação com o **🎓 Modo Tutorial interativo de 5 passos em direto**.
   - Ligar **apenas por Bluetooth** (desmarcando o Wi‑Fi para não levantar redes de 5 GHz) ou por Wi‑Fi rápido.
   - Receber as rotas com paragens de carregamento planeadas no carro e **abri-las automaticamente no Google Maps**.
   - **Fornecer Internet por Bluetooth** ao Planeador do carro sem gastar os 2 GB de dados da Leapmotor nem ligar o Hotspot.
   - **Atualizar a aplicação do carro num único toque** a partir do telemóvel (sem voltar a usar pendrives nem navegador).

---

## ⚠️ Antes de começar

- **Se já tinha o `Qonecta+` ou o `HeadQLink` instalado no telemóvel:** desative o arranque automático dessas apps enquanto usar o `Qonecta Unit`, para que não tentem abrir o Android Auto ao mesmo tempo.
- **Instale primeiro o Vivaldi no carro:** abra a loja oficial **`LeapAppStore`** no ecrã do Leapmotor C10 e transfira o navegador gratuito **Vivaldi** (caso ainda não o tenha instalado).

---

## 📱 PARTE 1 · Instalar o `Qonecta Companion` no seu telemóvel Android

1. Transfira e instale o **`QonectaUnitCompanion_0.1.19.apk`** a partir do [GitHub Releases](https://github.com/CharlysEV/qonecta-unit-companion/releases/latest) no seu telemóvel Android.
2. Abra o **Qonecta Companion** — a aplicação deteta automaticamente o idioma **Português** (ou pode escolher manualmente tocando no botão **`🇵🇹 PT`** no canto superior direito).
3. Conceda no primeiro cartão as permissões recomendadas tocando em **`Conceder`**:
   - **Bluetooth (Dispositivos próximos):** para detetar e ligar automaticamente ao seu C10.
   - **Notificações:** para manter a ponte ativa em segundo plano.
   - **Abrir o Google Maps automaticamente (*Sobrepor a outras apps*):** necessário no Android para abrir a rota enviada pelo carro com o telemóvel no bolso.
   - **Sem poupança de bateria:** para que o sistema não interrompa o serviço a meio de uma viagem.

---

## 🎓 PARTE 2 · Instalar o `Qonecta Unit` no C10 com o Modo Tutorial em Direto (apenas na 1.ª vez)

No **Qonecta Companion**, toque em **`🎓 Abrir Modo Tutorial passo a passo (C10)`** (no topo ou no Cartão 3). O assistente sincronizado acompanha-o em **5 passos automáticos** *(sem mexer em Proxy nem DNS)*:

### Passo 1 · Transferir o `Qonecta Unit` no telemóvel
- O tutorial verifica se já tem o `QonectaUnit_0.1.18-unit.apk` no telemóvel; caso contrário, toque em **«☁️ Transferir Qonecta Unit agora»** e será descarregado diretamente do GitHub.

### Passo 2 · Guardar o APK numa Pendrive USB‑C
1. Ligue uma **Pendrive USB‑C** ao telemóvel *(ou copie o ficheiro a partir de um PC para a raiz da pendrive)*.
2. Toque em **«💾 Guardar QonectaUnit na Pendrive USB»**, selecione a sua pendrive USB e guarde o ficheiro. O tutorial marcará automaticamente **`🟢 APK guardado!`**.
3. Ligue essa pendrive na **porta USB de dados do Leapmotor C10** (por baixo do apoio de braço central).

### Passo 3 · Ligar o Hotspot no telemóvel (zero Proxy · zero DNS)
1. Toque em **«📶 Abrir Ponto de Acesso (Hotspot)»** e ative a partilha de Wi‑Fi no telemóvel.
2. O tutorial deteta automaticamente o Hotspot (`🟢 Hotspot ativo detetado!`) e inicia o servidor local `:8788`.
3. Ligue o **Wi‑Fi do Leapmotor C10** ao Hotspot do seu telemóvel normalmente.

### Passo 4 · Abrir o Portal no Vivaldi + o truque das 2 setas `⤢`
1. Abra o navegador **Vivaldi** no ecrã do C10 e introduza o endereço curto indicado pelo tutorial (por exemplo `http://192.168.43.1:8788/portal`).
2. **Radar em direto:** no instante exato em que o Vivaldi abrir a página, o seu telemóvel ficará verde a confirmar **`🟢 SINCRONIZADO EM DIRETO! O seu Leapmotor C10 acabou de abrir o Portal`**.
3. No ecrã do carro, toque no **Botão Amarelo «📁 ABRIR FICHEIROS DO CARRO»**.
4. Na app **Ficheiros**, entre na sua **Unidade USB** à esquerda:
   - **🔑 O TRUQUE ESSENCIAL:** **NÃO toque no centro do ficheiro APK**. Toque nas **duas pequenas setas `⤢` no canto do ícone** (tal como mostra o esquema visual do tutorial) para abrir diretamente o Instalador oficial do sistema Android.
   - Aceite a permissão se for a primeira vez e toque em **Instalar**.

### Passo 5 · Emparelhar e sincronizar em tempo real
1. Abra o **Qonecta Unit** no seu C10 e toque no botão superior **«🔑 Vincular Companion»** para ver o código de **6 dígitos**.
2. No Passo 5 do telemóvel, escolha o Bluetooth do seu C10, introduza os **6 dígitos** e toque em **«🔗 Emparelhar e sincronizar agora»**.
3. Verá de imediato **`🎉 C10 ligado e sincronizado em tempo real!`** (já pode desligar o Hotspot e retirar a pendrive).

---

## ☑️ PARTE 3 · Escolher as opções de ligação (Só Bluetooth vs Android Auto)

Tanto no ecrã do **Qonecta Unit** (carro) como no **Qonecta Companion** (telemóvel) pode **marcar e desmarcar (`☑` / `☐`)** livremente cada modo:

- **🔵 Quero apenas dados, viagens, tarifas e enviar rotas (sem Android Auto nem Wi‑Fi 5 GHz):**
  Deixe marcado unicamente **`☑ Bluetooth`** e desmarque **`☐ Wi‑Fi 5 GHz`** (e desmarque **`☐ Cabo USB`** se quiser usar o cabo apenas para carregar a bateria). O carro e o telemóvel comunicarão 100 % por Bluetooth puro, sem levantar redes Wi‑Fi nem interferir com o rádio FM/DAB.
- **📡 Quero Android Auto sem fios:**
  Marque **`☑ Wi‑Fi 5 GHz`** no `Qonecta Unit` e toque em **«Conectar teléfono»**.
- **🔌 Quero Android Auto ou CarPlay por cabo:**
  Marque **`☑ Cabo USB`** e ligue o telemóvel à porta USB de dados do carro.

---

## ⚡ PARTE 4 · Atualizar no futuro NUM ÚNICO TOQUE (sem pendrive nem Vivaldi)

A partir de agora **nunca mais precisará de pendrive nem do Vivaldi** para atualizar a aplicação do carro:

1. No **Qonecta Companion** (Cartão 2 · *Atualizar Carro num Toque*), toque em **«☁️ Procurar nova versão na Internet»**.
2. Toque em **«⚡ Atualizar carro agora»**: o telemóvel envia a nova versão diretamente para o carro (com verificação `SHA-256`) e abre automaticamente no ecrã do C10 o instalador oficial do Android para tocar em **«Atualizar»**.

---

## ☕ Apoiar o projeto (`por CharlysEV`)

Se gosta de usar o **Qonecta Unit** e o **Qonecta Companion** no seu Leapmotor C10 e quiser oferecer um café ou apoiar o desenvolvimento contínuo:

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)
- 🐙 **GitHub:** [https://github.com/CharlysEV](https://github.com/CharlysEV)
