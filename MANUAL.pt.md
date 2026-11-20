# Qonecta Unit CORE · Manual do utilizador

**Idiomas:** [Español](MANUAL.md) · **Português** · [English](MANUAL.en.md) · [Italiano](MANUAL.it.md) · [Deutsch](MANUAL.de.md)

Guia para condutores do **Qonecta Unit CORE** (`v1.0.0-core`), desenvolvido por **[CharlysEV](https://github.com/CharlysEV)** ([CharlysEV/qonecta-unit-core](https://github.com/CharlysEV/qonecta-unit-core)).

> [!IMPORTANT]
> **Edição clássica / CORE — APENAS Android Auto e Apple CarPlay:**
> Esta versão foi concebida exclusivamente para projetar **Android Auto** e **Apple CarPlay** com a máxima estabilidade e leveza. **Não inclui o sistema Estendido** (sem painel lateral, sem ponte Companion, sem serviços de arranque em segundo plano).

> [!WARNING]
> Efetue toda a instalação e configuração **com o carro estacionado**. Nunca mexa no telemóvel ou em definições avançadas enquanto conduz.

---

## Índice

1. [O que é o Qonecta Unit CORE e requisitos](#1-o-que-é-o-qonecta-unit-core-e-requisitos)
2. [Instalação no Leapmotor C10](#2-instalação-no-leapmotor-c10)
3. [Ligar Android Auto (Wi-Fi 5 GHz, Hotspot ou USB)](#3-ligar-android-auto-wi-fi-5-ghz-hotspot-ou-usb)
4. [Ligar Apple CarPlay (Wi-Fi Direct, Hotspot ou USB)](#4-ligar-apple-carplay-wi-fi-direct-hotspot-ou-usb)
5. [Segundo ecrã opcional (Painel de instrumentos)](#5-segundo-ecrã-opcional-painel-de-instrumentos)
6. [Definições úteis e idioma](#6-definições-úteis-e-idioma)
7. [Resolução de problemas](#7-resolução-de-problemas)
8. [Reportar incidências no GitHub Issues](#8-reportar-incidências-no-github-issues)
9. [Apoiar o projeto e aviso legal](#9-apoiar-o-projeto-e-aviso-legal)

---

## 1. O que é o Qonecta Unit CORE e requisitos

O **Qonecta Unit CORE** instala-se diretamente no ecrã Android do **Leapmotor C10** e transforma a unidade num recetor nativo de **Android Auto** e **Apple CarPlay**, sem necessidade de dongles externos nem apps de espelho.

| Característica | Detalhe |
|---|---|
| **Protocolos** | **Android Auto** (telemóveis Android) e **Apple CarPlay** (iPhone) |
| **Modos de ligação** | Wi-Fi Direct 5 GHz · Hotspot · Cabo USB de dados |
| **Segundo ecrã (Painel)** | Opcional (`Painel: OFF` por defeito para máxima segurança; ativável com 1 toque) |
| **Edição CORE** | **Sem sistema Estendido**, sem captura de microfone (`RECORD_AUDIO` removido), sem serviços ao arrancar |
| **Idiomas dos menus** | Português, Español, English, Italiano, Deutsch (seleção manual disponível em Definições) |

**O que precisa:**
- Um **Leapmotor C10** (ou unidade Android compatível) onde possa instalar ficheiros APK.
- Uma **pen USB** formatada em FAT32/exFAT para a instalação inicial (ou transferência direta).
- O seu telemóvel **Android** (com a app *Android Auto* atualizada) ou o seu **iPhone**.

---

## 2. Instalação no Leapmotor C10

1. Transfira o ficheiro `QonectaUnit_1.0.0-core.apk` em **[GitHub Releases](https://github.com/CharlysEV/qonecta-unit-core/releases)**.
2. Copie o APK para uma pen USB e ligue-a à **porta USB de dados** do carro.
3. Abra o gestor de ficheiros do carro (ou navegador) e toque no ficheiro `.apk` para instalar.
4. Abra o **Qonecta Unit CORE** e conceda as permissões pedidas (**Dispositivos próximos / Bluetooth** e **Localização** para Wi-Fi Direct).
5. Em futuras atualizações pode usar diretamente o botão **«Ficheiros»** na barra superior da app.

---

## 3. Ligar Android Auto (Wi-Fi 5 GHz, Hotspot ou USB)

No ecrã inicial pode ativar um ou vários modos ao mesmo tempo:

### A. Wi-Fi 5 GHz (recomendado, sem fios)
1. Emparelhe o seu telemóvel por **Bluetooth** com o carro e mantenha o **Wi-Fi do carro ligado**.
2. Mantenha o modo **«Wi-Fi 5 GHz»** selecionado.
3. Toque em **«Ligar Android Auto»** ou toque diretamente no cartão do seu telemóvel em **«Telemóveis emparelhados»**.
4. Se notar cortes, desative *«Mudar para dados móveis»* nas definições Wi-Fi do telemóvel.

### B. Hotspot móvel (Internet no carro + Android Auto)
1. Ative o Hotspot no telemóvel e ligue o Wi-Fi do carro a essa rede.
2. Ative **«Hotspot móvel»** e toque em **«Ligar Android Auto»**.

### C. Cabo USB (Direto + Carga)
1. Mantenha **«Cabo USB»** ativo (funciona em conjunto com **Wi-Fi 5 GHz** sem conflitos).
2. Ligue o telemóvel à **porta USB de dados** do carro usando um **cabo USB de dados**.
3. O ecrã mostra imediatamente `🔌 USB pronto` e liga automaticamente — ou toque em **«Ligar USB»**.

---

## 4. Ligar Apple CarPlay (Wi-Fi Direct, Hotspot ou USB)

Toque em **«Apple CarPlay»** no ecrã inicial (ou ligue o iPhone por USB):

### A. CarPlay sem fios (Wi-Fi Direct 5 GHz — recomendado)
1. Emparelhe o **iPhone por Bluetooth** com o carro e mantenha Bluetooth e Wi-Fi ativos no iPhone.
2. No ecrã CarPlay, toque no seu iPhone em **«iPhones emparelhados»** ou em **«Ligar CarPlay»**.
3. Autorize o **CarPlay** no iPhone na primeira ligação.

### B. Hotspot do carro (alternativa)
Se o Wi-Fi Direct for instável no seu carro, selecione **«Hotspot do carro»**, introduza o nome e palavra-passe do Hotspot do carro e desligue o cliente Wi-Fi na barra superior do C10.

### C. CarPlay por Cabo USB
1. Ligue o iPhone à **porta USB de dados** do carro com um cabo de dados.
2. Toque em **«CarPlay USB»** (destaca-se automaticamente ao detetar o iPhone).
3. Desbloqueie o iPhone e aceite **«Confiar»** e **«CarPlay»**.
4. Na primeira vez, o Android pode pedir autorização para um **túnel VPN local** (estritamente local entre o USB e o ecrã, sem tráfego externo).

> **Dica no CarPlay:** deslize para baixo com **três dedos** para abrir as definições do CarPlay.

---

## 5. Segundo ecrã opcional (Painel de instrumentos)

Por segurança, o segundo ecrã vem **desativado por defeito (`Painel: OFF`)**.
- Toque em **`Painel: OFF`** na barra superior (em Android Auto ou CarPlay) para ativar **`Painel: ON`** e mostrar o mapa de navegação no painel de instrumentos do C10.
- Aplica-se na **próxima ligação**.

---

## 6. Definições úteis e idioma

- **Idioma:** abra **Definições → Idioma** para escolher **Português, Español, English, Italiano, Deutsch** ou idioma do sistema (aplica-se a Android Auto e CarPlay).
- **Escala / DPI:** ajustável nas **Definições** (Android Auto) ou **Definições CarPlay → Tamanho de ícones e texto**.
- **Saída limpa:** o botão **«Fechar»** encerra completamente Android Auto, CarPlay e painel sem deixar processos ativos.

---

## 7. Resolução de problemas

- **USB não detetado:** confirme que o cabo transmite dados e que está ligado à **porta USB de dados** do carro.
- **Wi-Fi 5 GHz não liga automaticamente:** toque no seu telemóvel em **«Telemóveis emparelhados»** e desative *«Mudar entre redes»* nas definições Wi-Fi do telemóvel.
- **CarPlay Wi-Fi Direct bloqueado:** vá a **Definições CarPlay → Ajuda e reinício do Wi-Fi CarPlay → Reiniciar Wi-Fi CarPlay**.

---

## 8. Reportar incidências no GitHub Issues

Se encontrar algum erro ou tiver sugestões, abra uma incidência em **GitHub Issues**:

👉 **[https://github.com/CharlysEV/qonecta-unit-core/issues](https://github.com/CharlysEV/qonecta-unit-core/issues)**

*(Também pode aceder a **Definições → Reportar um problema** dentro da app e ler o código QR com o telemóvel).*

Indique: modelo do carro/firmware, modelo do telemóvel e versão Android/iOS, protocolo (**Android Auto** ou **CarPlay**), modo (**Wi-Fi 5 GHz**, **Hotspot** ou **USB**) e, se possível, o registo exportado nas definições.

---

## 9. Apoiar o projeto e aviso legal

O **Qonecta Unit CORE** é gratuito, livre e sem anúncios (também acessível em **«☕ Apoiar»** na app com códigos QR offline):

- ☕ **Ko-fi:** [https://ko-fi.com/charlysev](https://ko-fi.com/charlysev)
- 💙 **PayPal:** [https://www.paypal.me/CharlysEV](https://www.paypal.me/CharlysEV)

Licenciado sob AGPL-3.0 / GPL-3.0. Projeto comunitário independente, sem afiliação à Leapmotor, Google ou Apple. Conduza sempre em segurança.
