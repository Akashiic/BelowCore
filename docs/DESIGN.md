# Akashic Bellow — Arquitetura

> Backport das camadas negativas (Y < 0) para Minecraft 1.7.10 (Forge 10.13.4.1614),
> desenhado para conviver com um modpack pesado (TEOF 4.1: Crucible, ChunkAPI, EndlessIDs,
> ArchaicFix/Phosphor, Hodgepodge, Angelica/Celeritas, Et Futurum Requiem).

## 1. Por que não CubicChunks

O CubicChunks troca o modelo de armazenamento inteiro (coluna → cubos 16³, mundo infinito na vertical,
geradores "cúbicos", camada de compatibilidade "vanilla generator"). Cada mod que toca chunks,
luz, pacotes ou renderização passa a ver um mundo com outra forma. O histórico dev.1→dev.7 do fork
mostra exatamente isso: corrigir GC de colunas, pacotes JoinGame/Respawn, rastreadores do Angelica,
sessões Bukkit, regeneração de cubos dentro de chunks vanilla… e ainda sobra comportamento aberto.

O objetivo aqui é menor e por isso pode ser mais seguro: **um piso fixo** (`minY`, padrão −64, até −512),
teto vanilla (256) intocado, e **nenhuma mudança no caminho Y ≥ 0**.

## 2. Princípio central: *Deep Path Isolation*

1. A coluna vanilla (`Chunk.storageArrays`, 16 seções, índices 0..15) **não muda de tamanho nem de significado**.
   Centenas de mods fazem `getBlockStorageArray()[y >> 4]` e iteram 0..15 — todos continuam corretos;
   eles apenas não enxergam o subsolo profundo (degradação graciosa, nunca corrupção).
2. As seções profundas ficam num array separado por chunk com 32 posições fixas:
   `slot = (y >> 4) + 32` para `y ∈ [−512, −1]` (`DeepConstants.slot`). Leituras respeitam o piso do mundo.
   São `ExtendedBlockStorage` reais, então EndlessIDs (que estende a EBS via mixin) funciona nelas sem código extra.
3. Os pontos de entrada com Y são desviados **somente quando `minY ≤ y < 0`**:
   * `World`/`ChunkCache`: a guarda vanilla `y < 0` / `y >= 0` é reescrita por expressão (MixinExtras
     `@ModifyExpressionValue` + `@Expression`) para `y < minY`. Abaixo do piso o comportamento é o vanilla.
   * `Chunk`: o acesso `storageArrays[y >> 4]` é redirecionado (`@Redirect` de `array=get`) para o array profundo
     quando `y < 0`. Seções profundas são criadas antes da escrita por um inject no HEAD (`ensureDeepSection`),
     e não por redirecionar o `array=set` (o idioma `DUP_X2` do javac produzia `VerifyError`).
   * TileEntity (`func_150812_a`): inject no HEAD com prioridade 900, antes da checagem fatal do Hodgepodge.
   O método original (ou o `@Overwrite` de outro mod) roda intocado para Y ≥ 0.
4. Todo ponto de injeção é `require = 1` (vários com `allow = 1`): se uma versão diferente de um mod mudar o alvo,
   o jogo **falha no boot** — nunca roda silenciosamente corrompendo o mundo.
5. Invariante: **o heightmap nunca fica negativo**. Céu nunca "enxerga" Y < 0 por heightmap; a luz do céu entra
   no subsolo só por propagação (BFS). Custo: um poço aberto até Y −30 recebe luz decaindo 1/bloco a partir de Y 0
   em vez de 15 cheio. Benefício: zero risco para mods que usam heightmap como índice ou laço `y > 0`.

## 3. Persistência

* **NBT por chunk** via `ChunkAPI ChunkDataManager` (`akashicbellow:deep`), nunca na lista vanilla `Sections`
  (o leitor do ChunkAPI/vanilla faria `array[Y]` com Y negativo → exceção → chunk regenerado).
  ```
  akashicbellow: { deep: { V: 1, Gen: byte, L: byte (luz pendente, só quando 1), P: byte (camada provisória, só quando 1),
                            Gens: { "<id do gerador>": { S: etapas feitas, F: etapas que falharam,
                                                         E: falhas de callback, V: versão, Data: {…} } },
                            Sections: [ {Y: byte(com sinal), <managers do ChunkAPI>} ] } }
  ```
  `L`, `P` (§5) e `Gens` (§5.1) entraram sem subir `V`: versões anteriores ignoram as duas chaves (e as descartam ao salvar de
  novo). Registros de geradores ausentes nesta sessão são mantidos e regravados intactos. Na leitura e no save eles
  são copiados: um chunk recarregado com o save ainda na fila é lido do mesmo compound que a thread de IO grava.
  Cada seção é serializada por `DataRegistryImpl.writeSubChunkToNBT`, ou seja, pelos mesmos managers que gravam as
  seções vanilla (inclusive os do EndlessIDs) — formato idêntico, IDs estendidos preservados.
* **Tile entities profundas** ficam fora da lista vanilla `Level.TileEntities`: no `ChunkDataEvent.Save` elas vão para
  `Level.AkashicBellowDeepTiles`, e voltam para a lista no início de `AnvilChunkLoader.loadEntities` (caminho síncrono
  e assíncrono). Sem isso, um TE com Y < 0 na lista vanilla derruba o carregamento do chunk sem o mod
  (`storageArrays[-1]`, ou a checagem do Hodgepodge). Chunks gravados por versões anteriores carregam igual e são
  convertidos no próximo save.
* **Versão do formato**: `V` é lido; dados profundos de um formato mais novo (downgrade do mod) não são carregados,
  ficam intactos e são regravados como estavam.
* **Trava por mundo**: o `minY` efetivo de cada dimensão é gravado em `<mundo>/akashicbellow.dat` (mapa `Floors`,
  escrita atômica) no primeiro uso; ao lado, `Generators` guarda os geradores profundos de cada dimensão (§5.1). Mudar a config depois não altera mundos existentes, e uma dimensão travada que
  sai da lista `dimensions` continua com o seu piso (com aviso no log): voltar ao piso 0 apagaria TEs e entidades
  profundas no próximo save. Arquivo corrompido → o servidor recusa subir (melhor que adivinhar um piso e perder
  dados).
* **Desinstalação**: vanilla/ChunkAPI ignoram as tags; o mundo carrega, perdendo o conteúdo abaixo de 0 (entidades
  que estavam lá caem no vazio). A bedrock legada que foi substituída (ver §5) não volta.

## 4. Rede

* Mod obrigatório nos dois lados, do mesmo build. O FML já recusa um cliente sem o mod ou de outra versão; um
  `@NetworkCheckHandler` (desde a beta.5) faz o cliente recusar também um servidor sem o mod, que o FML aceitava por
  padrão (`side == SERVER`) e que derrubava o cliente no primeiro `S28PacketEffect` (Y com sinal em `short`).
* `ChunkAPI PacketDataManager` (`akashicbellow:deep`) anexa as seções profundas a S21/S26:
  `short minY, int slotMask, depois para cada bit: int len + bloco de DataRegistryImpl.writeToBufferCubic`
  (os mesmos managers "cúbicos" do ChunkAPI/EndlessIDs). Pacote de descarregar e chunks sem dados profundos: vazio.
* Mudanças de bloco em Y < 0 não entram no `PlayerInstance` vanilla (que empacota Y em 8 bits). Fila própria:
  < limiar de agrupamento (Forge `clumpingThreshold`) → S23 individuais (o ChunkAPI já serializa a posição do S23
  num `long` com Y de 12 bits com sinal); ≥ limiar → S21 parcial só com as seções profundas tocadas, mais os
  pacotes de descrição das TileEntities.
* Pacotes vanilla com Y em byte sem sinal são alargados para `short` com sinal:
  `C07PacketPlayerDigging`, `C08PacketPlayerBlockPlacement`, `S28PacketEffect`, `S0APacketUseBed`.
  Auditados e já seguros: S23 (ChunkAPI), S24/S35/S33/C12 (`short`), S25/S29/S36/S0E/S10 (`int`),
  S27 (deslocamentos relativos). S22 nunca é usado para Y < 0.
* `DimensionInfoMessage` (canal `akashicbellow`) informa o `minY` da dimensão no login, troca de dimensão e respawn.
  O handler FML roda na thread do netty, então só registra o valor; ele é aplicado no tick do cliente.
* **Cliente**: ao receber seções profundas, as TileEntities delas são criadas na hora (como o Angelica faz para
  Y ≥ 0 no fim de `fillChunk`). O vanilla as criaria preguiçosamente, mas o meshing do Angelica lê um snapshot do
  mapa e proíbe criação nas threads de trabalho — sem isso baús e outros TESR abaixo de 0 não renderizavam.

## 5. Geração — o núcleo não gera conteúdo

Desde a beta.4 (API 4) o BelowCore é só o núcleo: ele traz as camadas negativas (armazenamento, motor, rede, luz,
API) e **não coloca conteúdo abaixo de 0**. O que a camada profunda é (preenchimento, piso de bedrock, minérios,
cavernas) vem dos geradores registrados na API (§5.1); no TEOF, do VitaGenesis. Antes da beta.4 o BelowCore
preenchia a camada (deepslate, piso de bedrock 1.18) e gerava os minérios da 1.18; isso foi para o VitaGenesis.

Estado por chunk (`Gen`): `0 NONE → 1 TERRAIN → 2 DECORATED`.

**TERRAIN** (em `Chunk.onChunkLoad`, servidor, dimensões habilitadas — cobre carga síncrona, assíncrona e geração):
1. As seções de `minY` até −1 são criadas, vazias (o que um chunk antigo já tinha abaixo de 0 fica).
2. `generateTerrain` de cada gerador: a camada inteira.
3. **Bedrock legada** (Y 0..4, só `Blocks.bedrock`) → deepslate do Et Futurum (ou pedra), **só nas colunas em que os
   geradores deixaram Y −1 sólido**: abre o mundo antigo para a camada nova, como a 1.18 fez, mas um gerador que falhou
   ou deixou a camada vazia não abre um buraco para o vazio. É a única escrita do núcleo em Y ≥ 0 (a regra de ouro do
   VitaGenesis, nunca escrever em Y ≥ 0, continua absoluta). Numa dimensão reclamada (API 6, issue #3) o dono escolhe o
   bloco (`IDeepGenerator.legacyBedrockReplacement`/`…Meta`): o terreno dele em Y 0 (end stone na Mining), `null`
   para o padrão ou `bedrock` para manter. As regras não mudam (só `bedrock`, só onde Y −1 ficou sólido, uma vez por
   chunk). Como a escrita vai direto para a seção, sem atualizar luz, só vale bloco opaco inteiro, sem luz e sem tile
   entity; outro bloco ou exceção recebe o padrão (aviso uma vez / erro no log). Em dimensão não reclamada ninguém é
   consultado.
* Retrogen: num mundo já explorado, todo chunk antigo é entregue aos geradores quando é carregado
  (`retrogen = true`), porque o terreno profundo é por chunk e não depende de vizinhos.

**Dimensão sem gerador** (nenhum instalado, ou nenhum que se aplique a ela; aviso no log ao carregar o mundo): os
chunks novos recebem uma **camada provisória** de pedra sobre um piso de bedrock, sem minérios nem cavernas, para que
nada abaixo de 0 seja vazio ao lado de chunks que têm camada (quem cavasse para o lado cairia até morrer). Esses chunks
continuam `NONE`, ficam com a bedrock natural em Y 0..4 e levam a marca `P` (§3), então a camada provisória é feita
uma vez só (um túnel cavado nela fica). O primeiro gerador instalado depois os recebe como chunks de retrogen e
substitui a camada (o VitaGenesis reescreve a pedra, mantendo o que jogadores puseram). `worldgen.provisionalLayer =
false` deixa esses chunks sem camada nenhuma (com a bedrock natural em Y 0, a superfície continua fechada).

**DECORATED**: roda para o chunk C quando C e os 8 vizinhos estão carregados com TERRAIN, e só em dimensões com
gerador: o `decorate` dos geradores. Vizinhança 3×3 (como a 1.18), nunca carrega chunk (sem cascata), escreve direto
nas seções; a luz vem da etapa de luz (§7). Chunks já vistos por jogadores recebem reenvio das seções alteradas.

**Primitivas de veio** (ficam no núcleo, usadas pelo VitaGenesis para todos os minérios): `IDeepRegion.placeOre` e
`placeBlob`, com o `OreFeature` da 1.18 portado linha a linha (segmento elipsoidal com `size` esferas, eliminação de
esferas contidas, `BitSet` de visita, `discardChanceOnAirExposure`; testes unitários da forma). Hospedeiros fixos:
pedra → minério comum; deepslate e tufo do Et Futurum → variante deepslate via
`ganymedes01.etfuturum.api.DeepslateOreRegistry` (cobre minérios de outros mods registrados no EFR); sem EFR, minério
comum.

**Fase de população legada.** Os geradores da 1.7.10 (decorador vanilla e todo `IWorldGenerator` de mod) foram
escritos para um mundo que termina em Y 0: veios e manchas sorteados perto do fundo contavam com a escrita abaixo de 0
ser ignorada. Com o pack inteiro (Mekanism, IndustrialUpgrade, Eln, fósseis, IC2, GC…) isso vazava centenas de minérios
de pedra para Y −1..−16, dentro do deepslate. Enquanto `Chunk.populateChunk` roda (delimitado com `@WrapMethod`,
`try/finally`, aninhamento contado para cascatas), `setBlock`/`setBlockMetadataWithNotify` abaixo de Y 0 mantêm o
comportamento da 1.7.10 (recusados). A geração profunda escreve direto nas seções e não é afetada.

Os avisos "cascading worldgen" do ArchaicFix vistos nos testes aparecem também com a nossa geração desligada
(36 na fase bootstrap do teste) e são emitidos dentro do `populate` vanilla — não vêm deste mod.

### 5.1 Geradores profundos (API)

Os geradores (o VitaGenesis no TEOF, ou de qualquer outro mod) fazem a camada profunda inteira; entram por
`BellowAPI.registerGenerator(id, IDeepGenerator[, prioridade])`. Ordem para um chunk, espelhando a 1.18 (terreno e
carvers, depois features, depois luz):

1. Bellow cria as seções de `minY` até −1, vazias.
2. `generateTerrain(chunk, minY, seed, retrogen)` de cada gerador, dentro do mesmo `Chunk.onChunkLoad`, antes de o
   estado virar TERRAIN. Depois, Bellow troca a bedrock legada onde Y −1 ficou sólido (§5).
3. Quando o chunk e os 8 vizinhos têm TERRAIN: `decorate(IDeepRegion)` de cada gerador. Estado DECORATED.
4. **Depois do tick do mundo** (fora de qualquer carga de chunk): seções alteradas reenviadas (uma vez por chunk e
   tick, máscaras somadas), depois `onDecorated(world, chunk)`.
5. **Depois do tick do mundo, quando o 5×5 em volta está carregado e decorado**: a etapa de luz do Bellow acende as
   fontes de luz do chunk (§7), depois `onLit(world, chunk)`.

Os passos 4 e 5 vêm de uma fila por mundo (`DeepStages`), processada no `WorldTickEvent` END com orçamento de
`stageBudgetMillis` (10 ms) por tick; o resto fica para o tick seguinte. Fora de uma carga de chunk, uma escrita pelo
`World` não é capturada em `captureBlockSnapshots` (que pode reverter tudo daquele momento) nem roda dentro de
`scheduledUpdatesAreImmediate` (fluido escorrendo recursivamente). Self-tests e ferramentas que carregam chunks fora
do laço de ticks chamam `BellowAPI.runDeepStages(world)`.

```java
// postInit (o mod declara required-after:akashicbellow)
// apiVersion() lança NoSuchMethodError com a API 1: chame dentro de try/catch
if (BellowAPI.apiVersion() >= 4) BellowAPI.registerGenerator("vitagenesis:caves", new IDeepGenerator() {
    @Override public String version() { return "1"; } // muda quando o formato da camada muda
    @Override public void generateTerrain(Chunk chunk, int minY, long seed, boolean retrogen) {
        for (int sy = minY >> 4; sy < 0; sy++) {
            ExtendedBlockStorage ebs = BellowAPI.getDeepSection(chunk, sy);
            // as seções chegam vazias: preencher, piso, cavernas. O que já não é ar (retrogen) foi posto por jogadores
            // ou mods e fica; a camada provisória do Bellow (pedra, bedrock no piso) pode ser substituída
        }
    }
    @Override public void decorate(IDeepRegion region) {
        // origens no chunk central; region.setBlock / placeOre / placeBlob no 3×3, só minY..-1
        // lava, líquen, ametista: sem luz aqui, a etapa de luz do Bellow acende
    }
    @Override public void onLit(WorldServer world, Chunk chunk) {
        // 5×5 carregado e decorado, luz pronta: ativar fluidos pelo World, atualizações de vizinho
    }
}, 0);
```

Garantias:
* Cada passo roda **uma vez por chunk, para sempre**. O que cada gerador fez fica gravado no chunk
  (`akashicbellow.deep.Gens`, §3; `BellowAPI.getGeneratorStages`): `onDecorated`/`onLit` que não puderam rodar
  (chunk descarregado antes do fim do tick, servidor parado) rodam quando o chunk carrega de novo. Um callback que
  lança exceção é tentado de novo nas cargas seguintes, no máximo 3 vezes.
* Chunks cujo subsolo foi gerado antes de o gerador existir nunca são entregues a ele (`getGeneratorStages` = 0):
  um gerador instalado depois não escava onde jogadores já construíram, nem acende ou ativa fluidos ali.
* Gerador adicionado a um mundo que já tem subsolo: os chunks na fronteira do que já existia (TERRAIN sem
  DECORATED) chegam ao `decorate` sem ter passado pelo `generateTerrain` dele (`getGeneratorStages` sem
  `STAGE_TERRAIN`); decore o que encontrar. `IDeepRegion.genStateAt` diz quais vizinhos já estavam decorados antes
  (possivelmente há muito tempo; `chunk.inhabitedTime` diz quanto tempo jogadores passaram ali).
* `retrogen` = o chunk foi salvo antes de ter camada profunda (mundo antigo recebendo o subsolo agora, ou chunk só com
  a camada provisória de quando não havia gerador); acima de 0 ele pode ter construções.
* **Ordem**: prioridade crescente, depois id, qualquer que seja a ordem de carga dos mods (que muda quando se adiciona
  um mod). A lista é congelada no `FMLServerAboutToStartEvent` (ou na primeira geração), registrada no log e gravada
  por dimensão em `akashicbellow.dat` (`Generators`, com `id@version`); se mudar entre sessões, aviso no log (as
  cavernas podem não continuar na fronteira com o que foi gerado antes). Registro tardio ou id repetido lançam
  exceção. `appliesTo(WorldServer)` escolhe as dimensões de cada gerador (perguntado uma vez por mundo).
* **Dimensão de um mod** (API 5, issue #2): `claimDimension(dimensionId, minY, generatorId)` até o
  `FMLServerAboutToStartEvent`, como `registerGenerator`. Para o mod dono de uma dimensão (o Akashic ATM Family com a
  Mining e a The Other, piso −64 num pack com `general.minY = -128`):
  * a dimensão tem camada profunda com o piso reclamado, esteja ou não em `general.dimensions`, e `general.minY` não
    vale para ela (`DeepWorlds.resolve`). A trava do save continua mandando: um save que já travou outro piso para a
    dimensão mantém o dele, com aviso, como com a config.
  * só o gerador dono roda nela: os outros não são consultados, nem o `appliesTo`, e não recebem `generateTerrain`,
    `decorate`, `onDecorated` nem `onLit` (é a mesma lista por mundo de `DeepGenerators.forWorld`). Sem camada
    provisória enquanto o dono estiver instalado.
  * um gerador que reclamou dimensões só roda nelas, sem `appliesTo`: sem isso, o gerador do ATM (com o `appliesTo`
    padrão) rodaria também no overworld, junto com o VitaGenesis.
  * reclamar uma dimensão já reclamada com outro gerador ou outro piso, piso fora de −512..−16 ou que não seja
    múltiplo de 16 e id vazio lançam `IllegalArgumentException`; a mesma chamada duas vezes é aceita; depois do início
    do servidor, `IllegalStateException`.
  * dono não registrado (id errado): erro no log, nenhum gerador roda na dimensão (camada provisória, trocada por
    retrogen quando o dono for registrado). Não derruba o servidor.
  * `getDimensionOwner(dimensionId)` e o `/bellow info` mostram o dono.
  * o dono escolhe o que substitui a bedrock legada na dimensão dele (API 6, §5 item 3).
* Uma exceção de um gerador é registrada no log (pilha completa nas 3 primeiras, depois só contagem) e o chunk segue;
  os geradores seguintes rodam no mesmo chunk. O que foi escrito antes da falha é marcado para salvar e reenviado
  (`finally`).
* **`StackOverflowError` não é engolido** (nem outro `VirtualMachineError`): os frames desempilhados podem ser cargas
  de chunk aninhadas, e o `originalLoadChunk` do Forge 1614 não tem `finally` (a chave fica em `loadingChunks`, o
  gerador vanilla fica com buffers pela metade). Derrubar o servidor com relatório é o resultado seguro.
* **Decorações nunca se aninham**: um chunk carregado de dentro de uma decoração (um gerador lendo o `World` fora da
  região) só entra numa fila; os 3×3 que ele completa são decorados quando a decoração externa termina. Isso também
  limita a profundidade de pilha que a decoração soma a uma carga de chunk.
* Os callbacks rodam fora da fase de população legada (`World.setBlock` abaixo de 0 funciona para eles).
* Chunks com dados profundos de formato mais novo (somente leitura nesta sessão) recusam escrita: `IDeepRegion`,
  veios, `World` e o próprio `Chunk` (que antes criava seção para luz ou bloco e perdia no save).

Contrato:
* Tudo na thread do servidor. `generateTerrain`: só o próprio chunk, só `minY..-1` por `BellowAPI.getDeepSection`
  (toda seção da faixa existe neste ponto, vazia num chunk novo), só blocos (sem TileEntity, sem luz, sem vizinhos, sem
  `World`), função de seed e posição (as bordas dos chunks batem em qualquer ordem de geração). Num retrogen, o que já
  não é ar foi posto por jogadores ou mods e deve ficar (a camada provisória do Bellow é a exceção). Deixe Y −1 sólido
  onde o mundo antigo deve abrir para a camada: a bedrock legada só é trocada ali.
* `decorate`: origens no chunk central; escrita no 3×3 por `IDeepRegion.setBlock` (remove a TileEntity antiga, cria a
  nova na hora), `placeOre` (veio da 1.18: pedra → minério, deepslate/tufo do EFR → variante deepslate do registro do
  Et Futurum) e `placeBlob` (forma de veio que substitui pedra, deepslate e tufo, como `ore_clay`), só `minY..-1`, sem
  luz nem atualização de vizinhos, sem escrita pelo `World`.
* `decorate`: um vizinho decorado depois coloca os seus veios, que podem entrar neste chunk (como na 1.18). Veios nunca
  substituem ar.
* `onDecorated`: o chunk está carregado; os vizinhos normalmente estão, mas confira (`chunkExists`) antes de depender
  deles. `onLit`: o 5×5 está carregado e decorado, a luz já está pronta e nenhuma decoração vai escrever no 3×3 em
  volta: escrita pelo `World` ali atualiza luz e vizinhos normalmente.
* Dados por gerador e por chunk: `BellowAPI.getGeneratorData(chunk, id)` (NBT pequeno, salvo junto do subsolo e
  mantido sem o addon; chame `setChunkModified` ao mudar).
* A decoração roda no **fim** do `Chunk.onChunkLoad` do chunk que completa o 3×3, já com as TileEntities e entidades
  dele registradas no mundo. O terreno continua no início, antes do `ChunkEvent.Load`.
* O reenvio após a decoração inclui os pacotes de descrição das TileEntities das seções alteradas (spawner, placa).
* **Modo estrito** (`worldgen.strictGenerators`, para testar geradores): durante `generateTerrain` e `decorate`,
  escrita pelo `World` que o contrato proíbe (qualquer uma no terreno; Y ≥ 0, abaixo do piso ou fora do 3×3 na
  decoração) é registrada no log e recusada. Escritas de uma população legada aninhada (um chunk que o gerador fez
  carregar) não são do gerador e passam.
* Estados novos de geração, se vierem, serão numerados acima de DECORATED sem mudar `V`: compare com `>=`
  (`BellowAPI.isDecorated`).
* A API pública é somente leitura: os setters (`setGenState`, `setDeepSections`, `setFromDisk`, `setMinY`) e o
  `getDeepSections` que aloca saíram de `IDeepChunk`/`IDeepWorld` para interfaces internas.

Self-test: ver §10.2.

## 6. Minérios

Saíram do BelowCore na beta.4: a lista de features da 1.18 (cascalho, tufo, ferro, ouro, redstone, diamante,
lápis, cobre, esmeralda), as alturas, as contagens e as configs `worldgen.deepBlock`, `worldgen.ores`,
`worldgen.scaleBottomAnchoredCounts` e `worldgen.oreCountMultiplier` agora são do VitaGenesis (categoria `layer`).
No núcleo ficam só as primitivas de veio (§5).

## 7. Luz

* **Vanilla**: o BFS de `World.updateLightByType` usa coordenadas relativas; com os desvios de
  `getSavedLightValue / setLightValue / getBlock / getBlockLightOpacity` e `checkChunksExist` ele atravessa Y=0 sem costura.
* **ArchaicFix Phosphor** (ativo no TEOF): o motor dele empacota Y em 8 bits e indexa
  `getBlockStorageArray()[y >> 4]`. Em mundos com camada profunda, o campo `lightingEngine` que o ArchaicFix adiciona
  ao `World` recebe um `DeepPhosphorEngine` (mesmo algoritmo do Phosphor, Y codificado como `y + 512` em 10 bits,
  bits 52..61). A troca é feita no `WorldEvent.Load` (todas as variantes de construtor de `World`, inclusive o
  servidor integrado), drenando antes as filas do motor antigo e limpando o cache de motor por chunk.
* **Etapa de luz da geração** (`DeepLight`, §5.1): a decoração escreve direto nas seções, sem luz (com os vizinhos
  só em parte carregados, `updateLightByType` seria ignorado). Um chunk decorado com geradores presentes fica com luz
  pendente (`L`, salvo); quando o 5×5 em volta está carregado e decorado, um flood fill a partir das fontes de luz do
  chunk (abaixo de 0) aplica a regra de `World.computeLightValue` (`max(emissão, vizinho − max(1, opacidade))`, emissor
  opaco deixa a luz entrar) no 3×3, só aumentando valores. Como nenhuma decoração escreve mais no 3×3 depois disso e a
  luz 15 anda no máximo 14 blocos, o resultado é final e independe da ordem; valores que o `World` já calculou (tochas
  de jogadores) ficam. Acima de 0 nada é tocado. Seções alteradas são reenviadas no mesmo ciclo. A camada provisória
  (sem gerador) não tem fontes de luz e não passa pela etapa.
* **Bordas no cliente**: a hipótese de que a luz profunda cortada numa borda nunca é refeita porque as flags de borda
  do ArchaicFix (`short`, 16 seções) não cobrem Y < 0 não procede como descrita: no ArchaicFix 0.8.0 essas flags só
  são marcadas para a luz do céu (`relightSkylightColumn`), que é 0 abaixo de Y 0; a luz de bloco não usa flags em
  nenhuma altura (o motor só ignora vizinhos descarregados). O caso real, luz gerada cortada na borda do que estava
  carregado, é o que a etapa de luz resolve (5×5).
* Detecção do Phosphor no coremod: classe do motor do ArchaicFix presente, Supernova ausente e
  `B:enablePhosphor` não desligado em `config/archaicfix.cfg`.

## 8. Cliente / renderização

* Renderer vanilla (`RenderGlobal`): grade vertical `16 + N` seções com deslocamento, `ChunkCache` com piso `minY`.
  Aplicado só sem Angelica.
* **Angelica/Celeritas**: o Celeritas já trabalha com `minSection/maxSection`. Mixins tardios (`@Pseudo`, só quando o
  mod está carregado) ajustam `AngelicaRenderSectionManager` (minSection, `isSectionVisuallyEmpty`),
  `getMinimumBuildHeight`, `ClonedChunkSection` (limite vertical e leitura da seção), o `WorldSlice` (seções
  profundas e piso da luz) e `CompatMathHelper.clampHeight`.
* Neblina do vazio e partículas de profundidade deslocadas por `minY`. Como na 1.18, o céu escurece só nos 32
  blocos acima do piso (no vanilla, de Y 32 a 0).
* Céu: abaixo do horizonte o `renderSky` desenha uma caixa preta sob a câmera até o fundo do mundo
  (`-(eyeY - 63 + 65)`, ou seja até Y −2). Abaixo disso a caixa ficava de cabeça para baixo e as paredes dela, a um
  bloco da câmera, cobriam o céu inteiro menos um quadradinho no zênite (medido: a −40 só 13% do centro da imagem
  era céu; a −10 uma faixa preta contornava a abertura). Medida a partir do piso, fica sob a câmera em qualquer
  renderer (o Angelica mantém o céu vanilla).

## 9. Compatibilidade conhecida

| mod | problema | tratamento |
|---|---|---|
| ChunkAPI 0.8.x | dono do NBT/pacotes | dependência obrigatória; managers próprios |
| EndlessIDs | formato de EBS | seções profundas são EBS reais; serialização pelos managers |
| ArchaicFix (Phosphor) | Y em 8 bits | `DeepPhosphorEngine` instalado por mundo |
| Hodgepodge | `MixinChunk` lança erro fatal para TileEntity em Y<0; remove a lista de chunks do cliente | mixin de TE com prioridade 900 resolve antes; instalador de luz tolera lista nula |
| Angelica | min/max de seção fixos; TEs criadas só para Y≥0 | mixins tardios; TEs profundas criadas na recepção |
| EFR | variantes deepslate dos minérios; deepslate na troca da bedrock legada | integração por reflexão (hospedeiros e `DeepslateOreRegistry` dos veios); camada EFR (Y 0..22) continua dele |
| Crucible/Spigot | `activeChunkSet` limpo no fim de `func_147456_g` | ticks aleatórios profundos injetados depois da chamada a `super.func_147456_g()` |
| Crucible: pistão | o corpo de `canExtend` vai para `canExtend_IntCB` (CraftBukkit devolve o tamanho do empurrão) | variante do mixin escolhida no coremod ao detectar o CraftBukkit |
| Crucible: `PlayerManager` | `chunkWatcherWithPlayers` vira `ConcurrentLinkedQueue` | fila acessada por `IDeepPlayerManager`, com uma implementação para Forge e outra para Crucible |
| Crucible: CraftBukkit | `CraftWorld.getBlockAt`, `CraftChunk.getBlock` e `CraftBlock` fazem `y & 0xFF`: Y −10 virava 246 para todo plugin e todo evento Bukkit | abaixo de 0 a máscara vira −1 (`y & −1 == y`); 0..255 inalterado |
| IC2 2.2.828 | Minerador para em Y 0 (5 comparações com 0); Magnetizador para em Y > 0 | mixins tardios: limites trocados pelo piso do mundo |
| Galacticraft 4.0 | foguetes (`EntitySpaceshipBase`) são destruídos no servidor se `posY < 0` | mixin tardio: só abaixo do piso |
| BuildCraft 7.1.63 | o cache de propriedades de bloco (`DimensionProperty`/`ChunkProperty`: "soft", minério, madeira…, usado por robôs, builders, fillers) guarda um bit em `x·4096 + y·16 + z` (x e z locais). Abaixo de 0, com x local 0 o índice é negativo: visto em produção em 2026-10-28, quebrar um bloco ali num chunk coberto pelo cache lançou `IndexOutOfBoundsException` em `World.markBlockForUpdate` e o jogador foi desconectado ("Internal server error"). Com x local > 0 não lança, mas lê e grava o bit de outro bloco (a coluna x − 1, 256 acima): respostas erradas em silêncio | mixins tardios: o cache cobre do piso ao topo (com piso 0 o layout é o do BuildCraft) e o preenchimento do cache começa no piso; fora da faixa lê falso e não grava |
| Crucible: colisão | o Spigot reescreve `getCollidingBoundingBoxes` e começa o Y em `max(k - 1, 0)`: nada colidia abaixo de 0 no servidor. Medido sem a correção: um porco cai da plataforma em Y −39 até Y −134 (itens disfarçam, porque são empurrados para fora de blocos) | mixin só no Crucible: teste e limite usam o piso |
| Crucible: blocos que caem | o CraftBukkit exige `j > 0` para o pouso: areia e cascalho viravam item abaixo de 0 (lido no bytecode do jar de produção) | mixin só no Crucible: `j > piso` |
| Forge: TEs ao enviar chunks | `func_147486_a(x, 0, z, …, 256, …)` coleta os pacotes de descrição só de Y ≥ 0 (placas, spawners, máquinas profundas ficavam no estado padrão no cliente); o envio rápido do Hodgepodge faz a mesma chamada | no próprio `func_147486_a`: pedido da coluna inteira (0 até o topo) começa no piso; o Crucible já percorre o mapa de TEs do chunk |
| Vanilla: portais | o `BlockPortal$Size` só desce `while (y > 0)` e o `Teleporter` procura e cria portais de cima até Y 0 | piso nos dois (variante do `Teleporter` para o Crucible, que move as buscas para `findPortal`/`createPortal`) |
| Vanilla/Forge: outros | Enderman só teleporta com `j > 0`; fluidos finitos do Forge somem em `y < 0`; entidades abaixo de 0 saíam e voltavam para o chunk a cada tick (`EnteringChunk` a cada tick) | piso nos testes; linha de entidade limitada a 0 como no `Chunk.addEntity` |
| Cliente vanilla (sem Angelica) | o `RenderGlobal` reserva listas/queries para 16 seções de altura; a grade mais alta estourava em distâncias de renderização altas | reserva para 16 + 32 seções |

Ouvintes de bloco (`IWorldAccess`) do pack, varridos depois do caso do BuildCraft: Armourer's Workshop, AE2, CoroUtil
(vazios), DragonAPI (repassa um evento), OpenComputers, Et Futurum (passa por `blockExists`, que conhece o piso),
JourneyMap (só chunk), Immersive Petroleum (coordenada fixa) e o sampler do spark (empacota a posição num `long`):
nenhum outro indexa um array por Y.

Os limites de IC2/GC foram encontrados por varredura de bytecode (comparações de `yCoord`/`posY` com 0 e acessos
diretos a `getBlockStorageArray`) e confirmados pela sonda de compatibilidade (§10), que os reprova sem os patches.
Ocorrências restantes da varredura são inofensivas para uma base profunda: coletor de oxigênio do GC (só conta folhas
em Y ≥ 0), entidades e geradores de dimensões espaciais (sem camada profunda), e falsos positivos.

### 9.1 Plugins Bukkit (servidor de produção)

O Crucible carrega as classes de plugin fora dos transformers do Forge: mixins não as alcançam (o
`AkashicWorldEditEndlessIDsCompat` do pack reescreve o jar do WorldEdit justamente por isso). O Bellow usa um Java
agent, que não altera nenhum arquivo do servidor.

| plugin | situação abaixo de 0 | caminho |
|---|---|---|
| GriefPreventionPlus 13.9 | claims 2D cortados na profundidade global `GriefPrevention.Claims.MaximumDepth` (0 no pack): nada abaixo de 0 é protegido. Os três usos do valor (proteção, baú do auto-claim, pontuação de investimento) só leem a configuração | configuração: `MaximumDepth: -64` (o piso) |
| WorldEdit 6.1.9 | seleções limitadas a Y ≥ 0, `EditSession` recusa Y < 0, máscaras e navegação param em 0 | agent `AkashicBellow-WorldEdit-6.1.9-*-agent.jar` |
| WorldGuard 6.2 | as consultas usam o Y real (correto com o Y corrigido do CraftBukkit); regiões novas vêm das seleções do WorldEdit; as existentes que vão de 0 ao topo não cobrem a camada nova | agent do WorldEdit + `tools/wgdeep.py` para listar as regiões a estender |

**Agent do WorldEdit** (`agent/`, build separado: `./gradlew -p agent build`). Entra na linha de comando como os
outros agents do pack (`-javaagent:AkashicBellow-WorldEdit-6.1.9-<versão>-agent.jar`).

* **Contrato com o mod**: no Crucible, o Bellow publica o piso de cada mundo como a propriedade de sistema
  `akashicbellow.floor.<nome do mundo Bukkit>` (em `WorldEvent.Load`). O código injetado lê só essa propriedade
  (`Integer.getInteger`), sem tocar em classes do mod, que o class loader do plugin não enxerga. Sem o mod, ou num
  mundo sem camada profunda, a propriedade não existe e o WorldEdit mantém o piso 0.
* **Tudo ou nada**: quando a primeira classe-alvo é definida, o agent calcula o SHA-256 de todas as 11 classes-alvo
  dentro do jar de onde ela veio. Se qualquer uma diferir do WorldEdit 6.1.9, nenhuma é alterada (um WorldEdit meio
  corrigido selecionaria abaixo de 0 e depois recusaria editar ali). O patch do AkashicWE-EID só reescreve
  `BaseBlock`, que não é alvo, então os hashes valem para o jar já corrigido por ele.
* **Classes já transformadas**: no Crucible, o class loader de plugins roda o remapeador SpecialSource
  (`remap-guava: true` no `cauldron.yml` troca `com/google/common` por `guava10/com/google/common`) antes dos agents,
  então os bytes que chegam ao agent não têm o hash do jar (medido: todas as classes carregadas; o código é idêntico
  ao do jar fora o nome do Guava e a ordem dos atributos). A identidade fica com o jar verificado; em cada classe, cada ponto
  ainda precisa casar exatamente, com a contagem esperada. O log diz `already transformed upstream` nesses casos, e
  `-Dakashicbellow.we.dump=<dir>` grava os bytes recebidos para diagnóstico.
* **Edições**: cada ponto é um padrão exato de instruções com contagem esperada; qualquer divergência rejeita a classe
  e mantém o bytecode original. As edições só trocam a constante 0 (ou 1) pelo piso, ou comparam com o piso em vez de
  com 0, sem novos desvios, de modo que os stack map frames continuam válidos. Pontos alterados:
  `DataValidatorExtent` (todo bloco escrito; piso lido uma vez por `EditSession`), `CuboidRegion` e
  `Polygonal2DRegion` (limites da seleção), `AbstractWorld.getMinimumPoint` (cilindro e esfera),
  `Polygonal2DSelection`/`CylinderSelection` (API Bukkit usada por outros plugins), `//chunk`, `//expand vert`,
  `//removebelow`, `//fill`, `//drain`, `//fixwater`/`//fixlava`, `//cyl`, o traço de mira (`/jumpto`, ferramentas)
  e a navegação (`/ascend`, `/descend`, `/ceil`, `/up`, `/unstuck`).
* **Fora do escopo, de propósito**: `//regen` (copia Y 0..255), restauração de snapshots (seções Anvil 0..15) e os
  laços de neve/grama/degelo, que descem a partir da superfície.
* **Testes**: `./gradlew -p agent test -Pbellow.weJar=<jar do WorldEdit> -Pbellow.serverJar=<server.jar>` aplica
  o patch às 11 classes com a contagem de pontos esperada (também depois do remapeamento do Guava do Crucible), sobe
  uma JVM com o agent para linkar todas
  (verificador de bytecode da JVM) e conferir seleção e validação no piso e abaixo dele, e confere que um jar com
  uma única classe diferente fica intocado.

**GriefPreventionPlus com `CreationRequiresWorldGuardBuildPermission: true`** (desligado no pack): o teste de
sobreposição com o WorldGuard monta uma região de Y 0 ao topo, então não enxerga uma região do WorldGuard que fique
inteira abaixo de 0. Não afeta a proteção dos claims.

## 10. Testes

* **Unitários** (`./gradlew test`): forma dos veios, empacotamento da luz, constantes, registro e ordem dos geradores
  (os testes de alturas, contagens e gradiente da bedrock saíram com o conteúdo, beta.4).
* **Servidor, 3 fases em JVMs separadas** (`tools/selftest.sh [-Pbellow.compatRun=true]`): cria o mundo com a
  geração desligada (chunks "legados"), religa e verifica retrogen, terreno (pelo gerador de teste `TestLayer`, §10.4),
  blocos/meta/TileEntity, luz,
  física no servidor (um porco fica de pé numa plataforma profunda, um item pousa, areia cai e vira bloco) e portal do
  Nether profundo (acende,
  sobrevive a uma atualização, o `Teleporter` encontra); depois uma JVM nova relê do disco, inclusive o NBT cru do
  chunk (TE profundo fora de `Level.TileEntities`). Roda sem mods e com EFR + EndlessIDs + ArchaicFix + Hodgepodge, e
  no Crucible de produção.
* **Sonda de compatibilidade com mods reais** (`-Dakashicbellow.selftest.phase=compat`, depois `compatverify` numa JVM
  nova; servidor de produção com o jar reobfuscado): coloca **todo bloco com TileEntity de todos os mods** (cada meta),
  toda entidade registrada e todo multibloco do Immersive Engineering na camada profunda e numa altura de controle com
  vizinhança idêntica; roda 1200 ticks; testa funcionalmente o Minerador e o Magnetizador do IC2; relê do disco.
  Só diferenças entre profundo e controle contam. Resultado com IC2 + IE + Galacticraft + EFR + EndlessIDs +
  Hodgepodge + NEI: 2059/2059 TileEntities iguais, 79/79 entidades (incluindo foguetes T1/T2/T3/carga), 14/18
  multiblocos do IE formados nas duas alturas (os outros 4 não formam pela sonda em nenhuma altura), 1691/1830
  TileEntities relidas byte a byte e as demais 139 mudam também no controle (estado que o próprio mod não salva,
  ex.: `growthPoints` das plantações do IC2). Sem os patches de IC2/GC a mesma sonda reprova 8 itens.
* **Pack TEOF 4.1 inteiro** (servidor Forge 1614, Java 8, ~200 mods; fora só os 8 jars que exigem Crucible/Bukkit e o
  lwjgl3ify, que é de cliente). Mesma sonda, com o controle numa base subterrânea acima de 0 (Y 8+, entidades e
  multiblocos em Y 30) e cada bloco numa cavidade de pedra selada:
  * 4123 blocos com TileEntity (variantes obtíveis), 777 entidades (foguetes GC/ExtraPlanets tier 1–10 incluídos),
    24 multiblocos IE/Immersive Petroleum (16 formados nas duas alturas, 8 a sonda não forma em nenhuma), IC2 funcional.
  * 4026/4123 idênticos na grade densa; os 97 restantes e as 20 entidades sinalizadas foram retestados **isolados**
    (`compatrecheck`, 20 blocos de distância): **97/97 e 20/20 idênticos**. A diferença na grade era interferência entre
    células (fluxo do Thaumcraft, conversão em netherrack, efeitos de área que atravessam paredes), com perdas nos dois
    sentidos (37 no fundo × 48 no controle).
  * Recarga em JVM nova: 3180/3531 idênticos, 287 com chaves que mudam também no controle. Os demais, nenhum
    específico do fundo: 61 ausentes **nas duas alturas** (o `soulJar` do ThaumicHorizons, colocado vazio, grava `null`
    no NBT e derruba o salvamento da coluna de chunk inteira), o refletor de feixe do GC (só resolve o alvo no primeiro
    tick depois de carregar) e listas do OpenComputers gravadas em ordem de hash (comparadas agora como multiconjuntos).
  * Artefatos que a sonda passou a evitar, todos iguais em qualquer altura: metas inexistentes declaradas com
    TileEntity (MFR, ElectriCraft), jogadores falsos (Gadomancy × OpenBlocks), entidades registradas com frequência de
    rastreamento 0 (poções do Witchery) e entidades de efeito criadas sem estado (ChromatiCraft).
* **Crucible de produção** (`tools/crucibletest.sh`; `server.jar` 1.7.10-staging-67b53034, pack inteiro, 66 plugins e os
  dois agents do servidor, linha de comando de produção): self-test completo PASS, incluindo a sonda Bukkit
  (`getBlockAt` mantém Y −10, tipo e luz corretos, `setTypeId` em −11 escreve em −11 e não em 245). O boot com o
  Bellow ficou em ~110 s. Achados do caminho:
  * O Mixin recusou subir enquanto o pistão e a fila do `PlayerManager` não tinham as variantes do Crucible (o
    comportamento desejado de `require = 1`).
  * Com `-Dterminal.jline=false` e a entrada padrão em fim de arquivo, o leitor de console do Crucible enfileira
    comandos vazios até esgotar o heap (2,7 GB em 3 min). O harness usa uma entrada aberta que nunca envia nada; em
    produção a entrada é o console do painel.
  * Num mundo **novo**, os primeiros chunks são gerados dentro do construtor do `World`, antes de o CraftBukkit
    registrar os vizinhos; o `updateLightByType` do Crucible (vindo do PaperSpigot) recusa toda atualização de luz
    neles, em qualquer altura (medido também acima do solo). Mundos existentes carregam o spawn pelo caminho normal.
* **Plugins abaixo de 0** (mesmo servidor, com o agent do WorldEdit como terceiro agent e, só na cópia de teste,
  `MaximumDepth: -64` no GriefPreventionPlus): self-test PASS. O piso chega aos plugins (`akashicbellow.floor.world=-64`);
  o WorldEdit seleciona até −64 (não 0), um `EditSession` preenche 3×11×3 = 99 blocos de −64 a −54 e recusa −65; uma
  região do WorldGuard em −62..−44 é encontrada por consulta dentro dela e não acima; um claim do GriefPreventionPlus é
  achado na superfície e em Y −59. Todas as 7 classes-alvo carregadas na sessão chegaram ao agent já remapeadas pelo
  Crucible (§9.1) e foram corrigidas com a contagem exata de pontos.
* **Custo** (`-Dakashicbellow.selftest.phase=bench`): 529 chunks novos, servidor de produção com EndlessIDs:

  | | sem camada | −64 | −512 |
  |---|---|---|---|
  | heap por chunk | 87 KB | 127 KB | 438 KB |
  | geração por chunk | 10,6 ms | 11,0 ms | 16,7 ms |
  | pacote de chunk | 4,0 KB | 5,2 KB | 9,8 KB |
  | disco por chunk | 6,8 KB | 7,7 KB | 11,7 KB |
* **Chunk loader** (`-Dakashicbellow.selftest.phase=chunkload`, ou `tools/crucibletest.sh <cópia> chunkload`): um
  ticket do Forge (o mesmo mecanismo dos chunk loaders do pack: BetterChunkLoaders/BCLForgeLib, usado pelo plugin
  ForbiddenChunkLoaders, e os do DragonAPI) força 3×3 chunks longe de qualquer jogador, com 0 jogadores no servidor.
  No chunk do meio, o mesmo conjunto é montado na superfície (Y 200) e na camada profunda (piso + 10): uma fornalha
  com minério e carvão (tick de TileEntity), 64 blocos de gelo sob glowstone (ticks aleatórios: o gelo derrete com luz
  de bloco acima de 8) e um porco (atualização de entidade). Uma área igual, só carregada e sem ticket, serve de
  controle. Após 1200 ticks:

  | | Forge (dev) | Crucible de produção (pack inteiro) |
  |---|---|---|
  | forçado, superfície | 6 lingotes, gelo 38/64, porco 1200/1200 | 5 lingotes, gelo 44/64, porco 1198/1200 |
  | forçado, profundo (−54) | 6 lingotes, gelo 32/64, porco 1200/1200 | 5 lingotes, gelo 32/64, porco 1198/1200 |
  | sem ticket, profundo | descarregado; gelo 0/64, porco parado | descarregado; nada roda |

  Esperado por bloco de gelo: 1 − e^(−1200·3/4096) = 58% (~37 de 64); o teste exige ao menos 20. Sem o mixin que
  injeta os ticks aleatórios das seções profundas, o profundo fica em 0/64 e é reprovado (verificado). Achados:
  * Um único chunk forçado sem os vizinhos carregados não recalcula a luz (`World.updateLightByType` exige os
    vizinhos), em qualquer altura: comportamento vanilla. Por isso o teste força 3×3.
  * O `forgeChunkLoading.cfg` de produção dá cota 0 a mods sem entrada própria (só BCLForgeLib, DragonAPI e
    Galacticraft têm cota). O teste precisa de uma entrada `akashicbellow` só na cópia; sem ela reprova com
    "Forge refused a ticket".
  * O plugin BungeeGuard desliga o servidor ao habilitar quando `settings.bungeecord` é `false` (cópia de teste
    fora do proxy). As fases que rodam ticks no Crucible precisam dele fora da cópia; a fase `full` não percebia
    porque termina antes do primeiro tick.
* **Cliente headless** (`tools/clienttest.sh [-Pbellow.compatRun=true -Pbellow.angelica=true]`, via `xvfb-run` e
  Mesa llvmpipe): mundo singleplayer, sala iluminada em `minY + 24`, tocha de parede colocada como jogador, baú;
  compara cliente × servidor (blocos, meta, TE, luz) e tira screenshots em `run/client/screenshots/`. Uma placa
  escrita abaixo de 0 num chunk que o cliente ainda não recebeu tem de chegar com o texto junto com o chunk (sem a
  correção do pacote de descrição, chega vazia: verificado).
  Estabilidade da imagem: numa sala fechada que atravessa o Y 0 (lã vermelha abaixo, azul acima) cada quadro
  renderizado é gravado em quatro sequências — câmera parada, subindo através do Y 0, girando 120°, e parada
  enquanto blocos da parede mudam acima e abaixo de 0 (seções reconstruídas) — e `tools/clientframes.py` verifica:
  nenhum pixel de céu ou vazio (seção não desenhada) em nenhum quadro; a junção do Y 0 reta e fechada em todo quadro
  da subida; com a câmera parada, a parede inteira dos dois lados do Y 0 em todo quadro e nenhum quadro que destoe
  dos vizinhos. Passa no renderer vanilla e no Angelica (~140 quadros cada); um buraco do tamanho de um bloco e uma
  fenda de 25 px na junção, injetados num quadro, são reprovados.
  Céu: num poço aberto do piso + 4 até o céu, ao meio-dia, a câmera olha para cima (FOV 30) em Y 100, 40, 10, −10 e
  −40; o centro da imagem tem de ser céu e a borda da abertura não pode ter faixa preta. Sem a correção da caixa do
  céu, −10 e −40 são reprovados; com ela passa no vanilla e no Angelica. Com o Angelica os quadros abaixo de 0 esperam
  460 ticks (malhas montadas fora da thread, lentas no GL por software; §10.4).

### 10.1 Segunda auditoria (inspeção por área)

Cinco revisões independentes (persistência, rede/cliente, motor/luz, geração/API, coremod/agent/ferramentas), cada
achado conferido no código e no fonte do Minecraft/Forge/Crucible antes de corrigir. Confirmados e corrigidos:

| gravidade | defeito | correção | evidência |
|---|---|---|---|
| alta | cliente que reentra numa dimensão profunda (Nether → Overworld, ou piso anunciado antes do S01) ficava com o motor de luz do ArchaicFix, que codifica Y em 8 bits: luz abaixo de 0 parava de atualizar no cliente | o tick do cliente instala o motor profundo uma vez por mundo/piso | teste de cliente: depois de ir ao Nether e voltar, um glowstone colocado só no cliente num bolsão escuro a Y −24 dá luz 14 ao lado; sem a correção dá 0 |
| média | `DeepTiles.restore` alterava a NBT que o Forge entrega ao `loadEntities`; num chunk recarregado com o salvamento ainda na fila, é o mesmo objeto que a thread de I/O grava (gravação sem a chave profunda, `ConcurrentModificationException` ou região corrompida) | o Forge lê uma cópia; o original nunca é alterado | self-test: o compound de entrada fica idêntico |
| média | TileEntities/entidades abaixo do piso atual (trava perdida, mundo movido entre servidores) derrubavam a carga do chunk (checagem do Hodgepodge) ou sumiam | ficam dormentes no chunk e voltam ao disco intactas, como as seções | self-test: dormentes não carregam e são regravadas |
| média | decoração no início do `onChunkLoad`: TileEntity criada por um gerador entrava duas vezes na lista de ticks (spawner em dobro) e a antiga não era removida | decoração no fim do `onChunkLoad` | self-test: 25 baús colocados na decoração, cada um uma vez na lista |
| média | TileEntity de mod criada no cliente ao receber o chunk era invalidada no mesmo pacote (laço final do `fillChunk` do ChunkAPI com `blockMetadata = -1`) | bloco e metadata preenchidos na criação | teste de cliente: barril do EFR abaixo de 0 válido (guarda de regressão: o pacote de descrição do servidor recria a TileEntity, então o teste ponta a ponta não reprova sem a correção; o efeito fica nas TileEntities sem descrição e no Angelica) |
| média | `//chunk` (sem `-s`) com o agent ia do piso até piso + 255, cortando o topo | `getMaxY` descontado do piso | teste do agent (estrutura do bytecode); sem a correção reprova |
| média | `tools/crucibletest.sh` podia aprovar uma rodada com o relatório de uma rodada anterior | apaga os relatórios antigos e só lê o da fase | — |
| baixa–média | formato NBT mais novo (downgrade) perdia TileEntities, entidades e edições abaixo de 0 | versão do ChunkAPI ligada ao formato com aviso de downgrade; dados dormentes; escrita abaixo de 0 recusada nesses chunks | self-test: aviso para o formato 2 |
| baixa–média | `API_VERSION` era constante de compilação (o `javac` copia o valor para o outro mod) | `BellowAPI.apiVersion()` | — |
| baixa–média | reenvio após a decoração não mandava os pacotes de descrição das TileEntities | enviados com as seções | — |
| baixa–média | seção ausente abaixo de 0 respondia luz de céu em `Chunk.getBlockLightValue` | 0, como os outros caminhos | — |
| baixa–média | renderer vanilla mantinha a geometria profunda de um chunk descarregado no cliente | a marcação de redesenho começa no piso | — |
| baixa | uma seção ilegível derrubava a carga do chunk inteiro (regenerado) | só ela carrega vazia e é regravada intacta | self-test |
| baixa | trava do piso: valor de tipo errado virava piso 0; falha ao gravar deixava a trava só na memória; sem `fsync` antes de renomear | recusa, grava antes de guardar no cache, `fsync` | — |
| baixa | minerador do IC2: o "não encontrado" (−1) é um Y válido abaixo de 0 | "não encontrado" = piso − 1 | — |
| baixa | portal abaixo de 0 não gerava zumbis-porco; ovo do dragão não caía abaixo de 0 | piso nos testes | self-test |
| baixa | config `deepBlock` aceitava ar, bloco com TileEntity, bloco que cai ou metadata > 15 sem EndlessIDs | recusados, com aviso | — |
| baixa | erros de gerador além de `RuntimeException` escapavam; erro na decoração deixava o chunk sem `onDecorated` | isolados e registrados | — |
| baixa | `DeepChanges` guardava o array de uma edição grande (WorldEdit) por chunk observado | volta ao tamanho base | — |
| baixa | `/bellow stats 16` em piso fundo podia travar o servidor | raio limitado pelo custo | — |
| baixa | agent: duas cópias na linha de comando (a antiga ganhava em silêncio); falha de patch parcial só num log genérico | a segunda cópia não arma; aviso de patch parcial explícito | — |
| baixa | `wgdeep.py` não lia coordenadas em notação científica (região do mapa inteiro) nem ids com aspas | corrigido; cuboide ilegível é marcado | arquivo sintético |
| baixa | scripts: `pgrep` pegava o `timeout` (dump de threads nunca saía), `rm -rf world` ignorava `level-name`, `sleep` órfão, `build/` inexistente | corrigidos | — |

Controles negativos desta rodada (cada correção desfeita, o teste reprova): o `//chunk` do agent (3 testes), a luz
calculada só pelo cliente depois da troca de dimensão (luz 0) e a decoração no início do `onChunkLoad` (TileEntity
duplicada). A luz vinda pelo servidor passava mesmo sem a correção: por isso o teste também coloca um glowstone só
no cliente.

### 10.2 Terceira rodada: checklist do VitaGenesis (API 3)

Origem: auditoria do VitaGenesis 0.1.0-beta.1 sobre o Below Core 0.1.0-beta.2 (`docs/AUDIT.md` §3 do VitaGenesis).
Cada item foi conferido no código (e no fonte do Forge 1614 / ArchaicFix 0.8.0) antes de mexer.

| item do checklist | veredito | o que mudou | evidência (self-test) |
|---|---|---|---|
| `DeepRegion.setBlock` aceita chunk NEWER (escrita perdida no save) | **defeito confirmado**; os veios do Bellow também entravam (`canWrite` olhava só a seção) | a região marca os chunks NEWER como somente leitura: `setBlock` e os veios recusam | vizinho NEWER: 0 blocos colocados nele, escrita do gerador recusada |
| (achado ao testar) `Chunk.setLightValue`/`func_150807_a` criavam seção em chunk NEWER; abaixo do piso caíam no ramo vanilla `storageArrays[-n]` (`ArrayIndexOutOfBounds` para mods que escrevem direto no chunk) | **defeito** | criação recusada nos dois casos e a chamada cancelada (como o `World` faz) | chunk NEWER sem seção, escrita recusada, nenhuma exceção abaixo do piso |
| `DeepDecorator` só pega `RuntimeException` e grava DECORATED antes; falha nos minérios não marca/reenvia os vizinhos e pula os geradores | **defeito confirmado** (o `catch` externo pulava o laço de reenvio e os geradores) | minérios e geradores isolados (`Throwable`, fatais relançados); marcação, reenvio e luz pendente num `finally` | minérios falhando de propósito + gerador falhando depois de escrever no vizinho: vidro salvo e na fila de reenvio, segundo gerador rodou |
| `rethrowFatal` engole `StackOverflowError` | **confirmado** (`originalLoadChunk` sem `finally`: chave presa em `loadingChunks`) | todo `VirtualMachineError` é relançado | teste unitário (SOE atravessa, o gerador seguinte não roda) |
| reentrância: decoração dentro de decoração/`onDecorated` | **confirmado** (um gerador lendo o `World` fora da região decorava outro chunk aninhado) | decorações nunca se aninham: carga durante decoração vai para fila, esvaziada no fim da externa; `onDecorated` saiu da carga de chunk | G carrega o último chunk do 3×3 de H: H decorado depois de G, 0 aninhadas |
| `onDecorated` numa fila no fim do tick, fora de carga e de `captureBlockSnapshots` | feito | `DeepStages`: fila por mundo no `WorldTickEvent` END, orçamento de 10 ms/tick, pendências salvas no chunk | 902 chunks decorados, `onDecorated` 1× cada, nenhum antes do tick nem dentro de decoração |
| etapa de luz no Below Core | feito, sem `markLight`: o Bellow varre as fontes de luz do chunk (pega também o que o `generateTerrain` escreveu) | `DeepLight` (5×5 decorado, flood fill pela regra do `World`, só aumenta), luz pendente salva (`L`), `onLit` depois | glowstone 15 / vizinho 14 só no chunk com 5×5 decorado; recalcular 512 posições pelo motor do `World` muda 0; luz e `onLit` pendentes retomados depois de reiniciar |
| luz profunda nas bordas no cliente (flags do ArchaicFix) | **não procede como descrito** (§7: as flags só existem para luz do céu) | — (o caso real é coberto pela etapa de luz) | leitura do `LightingHooks`/`LightingEngine` do ArchaicFix 0.8.0 |
| dados por chunk e por gerador | feito | `Gens` no NBT do subsolo: etapas, falhas, versão, `Data`; mantidos sem o addon, copiados no clone e na leitura | registros e dados lidos de volta numa JVM nova sem o gerador; escrita/leitura/clone como cópias independentes |
| lista e ordem dos geradores no save | feito | `Generators` por dimensão em `akashicbellow.dat` (`id@version`), aviso no log quando muda | lista gravada na fase full, mudança gravada e registrada na fase verify |
| `isDecorated` e estados ≥ 3 sem subir `V` | feito | `BellowAPI.isDecorated`; regra no javadoc de `DeepConstants`/`IDeepChunk` | — |
| expor o preenchimento | feito | `getFillBlock`/`getFillMeta`/`isDeepFill`/`isBaseStone`/`isDeepHost` | — |
| `placeOre`/`placeBlob` | feito | `IDeepRegion.placeOre` (variante deepslate do EFR) e `placeBlob` | (mesmo código dos veios do Bellow, `OreFeatureTest`) |
| estado de cada vizinho | feito | `IDeepRegion.genStateAt`; `chunk.inhabitedTime` via `chunkAt` | — |
| registro com metadados | feito em parte | prioridade (`registerGenerator(id, g, prioridade)`; ordem por prioridade e id, não pela ordem de carga), `appliesTo(WorldServer)`, `version()`; `contractVersion` não: um default no Bellow seria lido do jar do Bellow, não do gerador (sem valor); `apiVersion()` cobre a capacidade | segundo gerador (prioridade 10, registrado primeiro) roda depois |
| tirar setters do contrato público | feito | `setGenState`, `setDeepSections`, `setFromDisk`, `getDeepSections` (aloca, expõe slots dormentes) e `setMinY` em interfaces internas; javadoc aponta `getDeepSection` | (compilação do VitaGenesis contra o `-api.jar`) |
| modo estrito | feito | `worldgen.strictGenerators`: escrita proibida pelo `World` durante terreno/decoração registrada e recusada | 9 escritas no terreno e 2 na decoração recusadas, a de dentro da região aceita |
| juntar reenvios por tick | feito | máscaras somadas por chunk e enviadas no fim do tick (decoração e luz) | seções do chunk 2002,2002 na fila uma vez, com a máscara somada |
| gancho Y ≥ 0 (terragen 1.18) | fora do escopo | — | — |

Controles negativos (cada correção desfeita, o self-test reprova; cada um rodado nas 3 fases):

| controle (correção desfeita) | o que reprova |
|---|---|
| região sem a marca de somente leitura | vizinho NEWER: escrita do gerador aceita e 40 blocos de minério colocados nele |
| decorador da beta.2 no caminho de falha (falha dos minérios encerra a decoração) | vidro do gerador ausente, nada na fila de reenvio, segundo gerador não rodou |
| `StackOverflowError` engolido de novo | teste unitário: o SOE não atravessa e o gerador seguinte roda |
| sem a fila de cargas durante a decoração | H decorado aninhado dentro de G (1 aninhada) |
| `onDecorated` dentro da carga, como na beta.2 | 25 chunks da área com callback antes do tick; 902 chamadas dentro de decoração |
| sem a etapa de luz | glowstone com luz 0; o motor do `World` corrige 1 das 512 posições; na fase verify a luz não volta |
| `Gens` não gravado | registros não escritos; na fase verify etapas 0 e `onLit` nunca roda |
| modo estrito sem efeito | 9/9 escritas do terreno aceitas, 10 vidros acima de 0, 0 recusadas |
| `Chunk` sem a recusa de seção | seção criada no chunk NEWER e `ArrayIndexOutOfBoundsException: Index -5` abaixo do piso |
| ordem de registro em vez de prioridade | lista e `akashicbellow.dat` na ordem errada, segundo gerador roda antes |

Validação final desta rodada (mesmo código): unitários 27/27; Forge 3 fases (full 65 PASS, verify 13 PASS); Forge com
o pack de compatibilidade (65 / 13); cliente vanilla (27), com o pack (28) e com Angelica (28), frames incluídos;
Crucible de produção, fase full: 76 PASS (o pack gera 54 chunks a mais em cascata na área A; o modo estrito recusou
exatamente as 11 escritas proibidas).

Compatibilidade com o VitaGenesis 0.1.0-beta.1 (commit `98efcfd`, compilado contra o `-api.jar` desta versão,
`tools/selftest.sh -64` dele, 3 fases):
* sem alteração: compila e roda; reprovam só as verificações de luz do self-test dele (0/81 chunks iluminados pela
  fila dele, checksum do reload diferente). O harness dele carrega chunks no `FMLServerStartedEvent`, sem ticks, e o
  `onDecorated` (que alimenta a fila dele) agora roda depois do tick. Em jogo os ticks rodam, e o handler de tick do
  Bellow (registrado no preInit) vem antes do dele (registrado no init) no mesmo tick.
* com `BellowAPI.runDeepStages(world)` antes de cada `DeepLighting.processPending` do self-test (4 linhas): **PASS nas
  3 fases**, incluindo "luz igual ao motor vanilla" (0 de 3672/7668/3942/9828 células mudam ao recalcular) com a etapa
  de luz do Bellow e o flood dele rodando juntos (os dois só aumentam valores pela mesma regra), e nenhum erro do
  Bellow no log.
* O que o VitaGenesis pode remover quando adotar a API 3: a fila e o flood próprios (`DeepLighting`, trocar por
  `onLit` para a ativação de fluidos), as marcas `Ours`/`Lit` em `Level.VitaGenesis` (trocar por
  `getGeneratorStages`), parte do `vitagenesis.txt` (a lista de geradores já fica em `akashicbellow.dat`;
  `version()` com as settings), a heurística "sólido sem TileEntity é pedra" (trocar por `isDeepFill`/`isBaseStone`).

Rodadas anteriores do `GeneratorProbe` (API 2), ainda cobertas: terreno uma vez por chunk novo (49/49); decoração uma
vez, depois do terreno dos 9 chunks e depois dos minérios; câmara intacta após os minérios; 75/75 escritas aceitas
(incluindo baú com TileEntity e escrita no vizinho) e 75/75 recusadas (Y 0, abaixo do piso, fora do 3×3); chunks fora
da área sem alteração; `retrogen` igual a "chunk salvo antes do subsolo"; com minérios desligados, o chunk ainda chega
a DECORATED, sem minérios; um callback chamado depois de uma carga dentro de uma população legada (simulada) escreve
pelo `World` abaixo de 0. Controles daquela rodada: sem a chamada de `generateTerrain` (49/49 chunks sem terreno,
câmaras cheias); com o bug antigo de `ores = false` (chunk parado em TERRAIN); gerador antes dos minérios (15 de 25
chunks decorados sem minério ainda); sem suspender a fase legada (escrita recusada).

### 10.3 BuildCraft abaixo de 0 (kick em produção, 2026-10-28)

Sintoma: jogador desconectado com "Internal server error" ao quebrar um bloco abaixo de 0, depois de abrir um poço com
`//cyl`. Causa (log do servidor): `IndexOutOfBoundsException: bitIndex < 0: -139` em
`buildcraft.core.properties.ChunkProperty.set`, chamado por `World.markBlockForUpdate` (§9). Varredura dos outros
ouvintes `IWorldAccess` do pack: nenhum outro indexa por Y.

Teste (`BuildCraftProbe`, fase full, só quando o BuildCraft está instalado: cópia do Crucible de produção), pela API do
BuildCraft (`getWorldProperty("soft")`): pedra e ar em Y −20 depois de o cache existir (troca notificada por
`World.markBlockForUpdate`, o frame do kick), bolsão de ar feito antes do cache (laço de preenchimento), Y 250, piso − 5
e Y 300 sem exceção, e o próprio caso do kick (x local 0, Y −9).

| rodada | resultado |
|---|---|
| com a correção | PASS (77 no total): pedra falso, ar verdadeiro, pedra de novo falso, bolsão verdadeiro, ar em x 0 verdadeiro |
| sem os mixins (controle) | FAIL: `IndexOutOfBoundsException: bitIndex < 0: -139` (o mesmo índice do log de produção) e, antes dele, pedra lida como "soft" (o bit de outro bloco) |

### 10.4 Só o core: o conteúdo sai do BelowCore (beta.4, API 4)

Política nova do ecossistema: o VitaGenesis é o provider da camada negativa e o BelowCore só o core (lista do agente do
VitaGenesis, `docs/BELOWCORE-HANDOFF.md` dele, conferida no código). Saíram: o preenchimento e o piso de bedrock
(`DeepTerrain.fill`, gradiente, `PositionalRandom`), a lista de minérios da 1.18 (`DeepFeatures`, `DeepFeature`,
`HeightProvider`, `Anchor`, `placeFeatures`), a escolha do bloco de preenchimento, as configs `deepBlock`, `ores`,
`scaleBottomAnchoredCounts` e `oreCountMultiplier`, e na API `getFillBlock`, `getFillMeta`, `isDeepFill`, `isDeepHost`,
`isBaseStone` (o VitaGenesis não usa nenhum). Ficam as primitivas de veio, os hospedeiros fixos, a troca da bedrock
legada e todo o resto do core. Decisões além da lista:
* **Sem gerador**: em vez de recusar a camada (impossível numa dimensão já travada) ou de um preenchimento mínimo
  definitivo (costura permanente quando o provider volta), a camada provisória fica marcada (`P`) e o chunk continua
  NONE: nada é vazio ao lado de chunks com camada, e o provider a substitui por retrogen.
* **Bedrock legada depois dos geradores e só onde Y −1 ficou sólido**: um gerador que falhar não abre um buraco para o
  vazio (antes ela era trocada antes dos geradores, com o preenchimento garantido pelo Bellow).

Teste: o self-test registra nas fases full e verify um gerador de teste (`TestLayer`: pedra com bedrock no piso, o papel
do provider) e o `ProvisionalProbe`, que esconde os geradores para o caso sem provider.

| verificação | resultado |
|---|---|
| geradores recebem todas as seções, vazias | 634 chamadas de terreno, 0 seções faltando, 0 com blocos |
| bedrock legada trocada onde Y −1 é sólido, mantida onde ficou aberto | 0 blocos de bedrock legada nas colunas sólidas; a coluna aberta mantém a bedrock em Y 0 |
| sem gerador: camada provisória, chunk NONE, bedrock legada mantida | pedra sobre bedrock; 9/9 chunks NONE e provisórios; 2304/2304 colunas com bedrock em Y 0; 0 chamadas de gerador |
| a camada provisória é feita uma vez | túnel cavado nela continua aberto depois de outra carga |
| (verify, JVM nova, com gerador) o gerador toma a camada provisória | estado TERRAIN, marca limpa, o gerador recebeu retrogen sobre a pedra, 0 bedrock legada restante |

| controle (correção desfeita) | o que reprova |
|---|---|
| camada provisória refeita a cada carga | túnel tapado de novo |
| bedrock legada trocada sem a regra da coluna | a coluna aberta perde a bedrock em Y 0 |
| fallback marcando o chunk como gerado | 9 chunks fora de NONE; na verify o gerador nunca recebe o chunk e restam 788 blocos de bedrock legada |
| núcleo preenchendo antes dos geradores (o comportamento antigo) | 36 seções chegam com blocos ao gerador (`bedrock`/`stone` em 0,0,0); a coluna aberta perde a bedrock |

Três achados da validação, nenhum deles defeito do core:
* **ArchaicFix muda `ExtendedBlockStorage.isEmpty()`**: com a iluminação dele (`MixinExtendedBlockStorage`, estilo
  Phosphor) a seção só é vazia se também a luz for a padrão. Na sonda de compatibilidade as 36 seções novas do 3×3 em
  1000,1000 respondiam "não vazia" com 0 blocos (contador de blocos 0, varredura de 4096 posições só com ar). O teste
  agora varre os blocos (o contrato é "sem blocos"); geradores não devem usar `isEmpty()` para saber se uma seção
  profunda tem blocos.
* **Angelica, céu a −40**: com a camada provisória o quadro de 60 ticks mostrava o poço atravessado (seções da parede
  junto à câmera ainda sem malha: viam-se as seções da superfície atrás) — borda 0,21 preta, igual em 3 execuções.
  Despejo do cliente no mesmo instante: todas as seções profundas do 5×5 presentes, com os mesmos blocos que 400
  ticks depois, quando o quadro fica completo (borda 0,02). É latência do Celeritas montando malhas fora da thread
  no GL por software, não dado faltando. O teste espera 460 ticks antes dos quadros abaixo de 0 com o Angelica. Por
  que a beta.3 cabia em 60 ticks não foi determinado. Tentativas descartadas: esperar até não haver seção "visível e
  não montada" (`isSectionVisible`/`isSectionBuilt`) nunca zera (19–32 seções ficam assim indefinidamente);
  fixar o meio-dia exato põe o sol no centro da imagem e reprova "meio = céu" em qualquer renderer.
* **Crucible com o modpack inteiro: o chunk que fecha a região já estava carregado.** A sonda "um gerador que falha
  mantém, salva e envia o que escreveu" carrega 8 vizinhos, esvaziava a fila de estágios e só então carregava o nono,
  que completa a região do centro. No Crucible algum gerador do pack, ao popular os vizinhos, carregou esse nono chunk
  antes: a decoração aconteceu dentro do laço e o esvaziamento da fila mandou as seções antes da verificação
  ("seções enfileiradas 0", reprovado uma vez em duas execuções). O esvaziamento intermediário saiu (a seção do vidro
  só é enfileirada por essa decoração e nenhum tick roda entre as cargas) e a sonda registra quando a decoração
  aconteceu antes ("decorated before its region was completed here", visto na execução seguinte, que passou).
  Controle: sem o `DeepStages.resend` no fim da decoração a sonda reprova ("seções enfileiradas 0"), junto com a de
  envio no fim do tick.

Validação final (beta.4):

| execução | resultado |
|---|---|
| `tools/selftest.sh` (Forge puro) | full 61 PASS, verify 14 PASS |
| `tools/selftest.sh -Pbellow.compatRun=true` (EndlessIDs, Et Futurum, Hodgepodge, ArchaicFix) | full 61 PASS, verify 14 PASS |
| `tools/clienttest.sh` vanilla / compat / Angelica | 27 / 28 / 28 PASS, quadros PASS nos três; céu a −40: borda 0,02 / 0,03 / 0,02 |
| Crucible (cópia de teste, `tools/crucibletest.sh full`) | 73 PASS (com a sonda corrigida; a decoração antecipada apareceu) |
| self-test do VitaGenesis (a60497d, API 3) contra a beta.4, piso −64 | bootstrap, full e reload PASS |

### 10.5 Sair da água abaixo de 0 (relato de jogador, 2026-10-29)

Relato: numa corrente de água abaixo de 0, segurando espaço contra a borda, o jogador não sai da água; acima de 0
sai. Causa no vanilla: `World.isAnyLiquid` e `World.checkBlockCollision` fazem `if (box.minY < 0.0D) --minY`, como se
`floor` arredondasse para zero (não arredonda). Abaixo de 0 elas examinam também o bloco sob a caixa. Para sair da
água o vanilla testa `isOffsetPositionInLiquid` com a caixa erguida 0,6 (`EntityLivingBase.moveEntityWithHeading`),
que abaixo de 0 achava a água de baixo e nunca deixava subir na borda. Correção: a condição de Y das duas é falsa
(`MixinWorld_Deep`, `require = 2`); X e Z ficam como no vanilla. Acima de 0 a condição nunca era verdadeira, então
nada muda lá. A correção vale para o cliente, que move o próprio jogador. `checkBlockCollision` só pesa no teste de
voo do servidor (ficava mais tolerante abaixo de 0).

Teste (`WaterProbe`, fase full): um `FakePlayer` numa piscina de 2 blocos com borda 1 bloco acima da água anda contra
a borda com pulo segurado, executando o movimento vanilla tick a tick, em Y 100 (controle) e em piso + 30.

| execução | Y 100 | Y −34 |
|---|---|---|
| sem a correção | na borda em 35 ticks (x 3,06, y 102,30) | ainda na piscina após 200 ticks (y −32,72) |
| com a correção | na borda em 35 ticks (x 3,06, y 102,30) | na borda em 35 ticks (x 3,06, y −31,70) |

Com a correção: Forge puro 63 PASS e com o pacote de compatibilidade 63 PASS (as duas sondas iguais às da tabela);
clientes vanilla e Angelica PASS (a mixin aplica no cliente: com `require = 2` o jogo não abriria sem ela); Crucible
(cópia de teste) 75 PASS, com as duas sondas como acima. O nado em si não é exercitado no cliente headless.

### 10.6 Issues #1 e #2 (beta.5, API 5)

**#1, cliente com o mod num servidor sem ele.** Conferido no fonte do FML 1614: sem a entrada do mod na lista do outro
lado, o `DefaultNetworkChecker` responde `side == Side.SERVER`, e com o mod nos dois lados exige a mesma versão.
O `@NetworkCheckHandler` do `AkashicBellow` exige o mod do outro lado **com a versão que o próprio FML anuncia**
(`ModContainer.getVersion()`): só o caso do servidor sem o mod muda; checar só a presença, como sugerido na issue,
deixaria entrar builds diferentes. `NetworkCheckProbe` passa pela decisão do FML (`NetworkModHolder.check` e
`FMLNetworkHandler.checkModList`, chamados pelos dois estados do handshake).

**#2, dimensão de um mod** (§5.1). `ClaimProbe`: o End (dimensão 1, fora de `general.dimensions`) reclamado por um
gerador de teste com piso −48 (config −64); na verify, reclamado com −32.

| verificação | resultado |
|---|---|
| servidor sem Bellow (lista do servidor vista pelo cliente) | recusado: `Mod rejections [FMLMod:akashicbellow{…}]` |
| mesmo build aceito; cliente sem Bellow, outra versão e vanilla recusados | sim (os três já eram recusados antes) |
| reclamações conflitantes (outro dono, outro piso), piso −40, id vazio | recusadas; a mesma reclamação duas vezes aceita |
| reclamação depois do início do servidor | recusada |
| piso da dimensão reclamada | −48 (config −64, End fora de `general.dimensions`) |
| só o dono gera ali | 9 chamadas de terreno, piso de obsidiana em 2304/2304 colunas, geradores do End = só o dono; a camada de teste (que vale em qualquer dimensão) não rodou no End |
| o dono não gera em outra dimensão | dono só no End, camada de teste só no overworld |
| (verify, JVM nova, reclamado com −32) a trava do save vence | End em −48, piso gerado mantido |
| unidade (`DeepGeneratorsTest`) | exclusividade, nenhum `appliesTo` perguntado na dimensão reclamada, conflitos, piso inválido, tarde demais, dono não registrado |

No Crucible com o pack inteiro, `checkModList` com a própria lista do servidor também é recusado pelo
`salisarcana` (verificação própria dele); a sonda conta só o veredito do Bellow. Na cópia do Crucible o End já tinha
chunks explorados com piso 0: reclamado, recebeu o subsolo do dono por retrogen (1295 chamadas de terreno). É o que
acontece com qualquer dimensão já explorada que passa a ter camada (por config ou por reclamação): o dono recebe esses
chunks com `retrogen = true` e decide o que fazer.

| controle (correção desfeita) | o que reprova |
|---|---|
| sem o `@NetworkCheckHandler` | servidor sem Bellow aceito (FML: `null`); vanilla aceito |
| reclamação não exclusiva (todos os geradores rodam na dimensão) | geradores do End = os 4; a camada de teste gera no End e cobre o piso do dono (0/2304 colunas); na verify o piso gerado sumiu |
| piso ignorando a reclamação | End com minY 0, dono sem chamadas; na verify, minY 0 |
| dono rodando também fora das suas dimensões | dono gera no overworld: 25 câmaras de outro gerador preenchidas (12.725 blocos), lista de geradores do overworld errada, etapa de luz e retomada quebradas — o que aconteceria com o ATM ao lado do VitaGenesis |

Validação (beta.5):

| execução | resultado |
|---|---|
| unidade | `DeepGeneratorsTest` 9/9 (3 novos) e os demais |
| `tools/selftest.sh` puro / com o pacote de compatibilidade | full 72 PASS, verify 15 PASS nos dois |
| `tools/clienttest.sh` vanilla / compat / Angelica | 27 / 28 / 28 PASS, quadros PASS (o handshake do servidor integrado passa pela mesma verificação) |
| Crucible (cópia de teste, pack inteiro) | 84 PASS |
| self-test do VitaGenesis (API 3) contra a beta.5, piso −64 | bootstrap, full e reload PASS |

### 10.7 Issue #3: o dono escolhe o que substitui a bedrock legada (beta.6, API 6)

Caso real (ATM, relatado na issue): a Mining gerada com piso 0 (beta.4, sem reclamação) tem bedrock em Y 0 no meio da
faixa de end stone; com a reclamação, o retrogen trocava essa bedrock por deepslate em todas as colunas. Agora o dono
responde por posição.

Teste (`ClaimProbe`, fase full): um chunk do End reclamado feito com os geradores escondidos (como uma dimensão ainda
sem camada), com bedrock em Y 0..4 e a coluna 15,15 aberta em Y −1, passa pela etapa de terreno. O dono responde por
altura. A camada de teste, que não é dona de nada, responde end stone no overworld.

| verificação | resultado |
|---|---|
| Y 0: terreno do dono (end stone; lã meta 5 na metade oeste) | 255/255 colunas |
| Y 1: `null` → padrão | 255/255 |
| Y 2: `bedrock` → mantida | 255/255 |
| Y 3: vidro (não opaco) → recusado, padrão, aviso uma vez | 255/255 |
| Y 4: exceção → padrão; 3 rastros completos, depois só contagens em potências de 2 | 255/255 |
| coluna aberta em Y −1 | mantém a bedrock em Y 0..4 (5/5) |
| overworld (não reclamado): resposta da camada de teste ignorada | 245 colunas com o padrão em Y 0, 0 com end stone |

| controle (correção desfeita) | o que reprova |
|---|---|
| dono ignorado (sempre o padrão) | Y 0 com o terreno do dono 0/255, Y 2 mantida 0/255 |
| sem a checagem do bloco | o vidro é escrito (Y 3 com o padrão 0/255) |
| qualquer gerador consultado onde não há dono | overworld com end stone da camada de teste em 255 colunas |

Validação (beta.6): Forge puro e com o pacote de compatibilidade, full 74 PASS e verify 15 PASS; cliente vanilla 27 PASS
e quadros PASS; Crucible (cópia de teste) 86 PASS, com as duas sondas novas; self-test do VitaGenesis (API 3) contra a
beta.6: bootstrap, full e reload PASS.

## 11. Riscos abertos (beta)

* Crucible: o servidor de produção foi testado (§10), sem jogadores reais conectados; os plugins foram exercitados
  pelas suas APIs, não por comandos de jogador. Proteção abaixo de 0 exige o agent do WorldEdit na linha de comando,
  `MaximumDepth` no piso no GriefPreventionPlus e as regiões antigas do WorldGuard estendidas (§9.1).
  `//regen` e restauração de snapshots continuam limitados a Y ≥ 0.
* Memória (medida, §10): −64 custa +40 KB por chunk carregado (+46%); −512 custa +351 KB (5×). Com distância de
  visão 10 (441 chunks por jogador isolado) são ≈ +18 MB por jogador em −64 e ≈ +150 MB em −512, no servidor e no
  cliente. Recomendado: −64 (padrão) a −128; −512 só com RAM sobrando e poucos jogadores espalhados.
* Spawn natural: a altura é sorteada uniformemente entre o piso e o topo do chunk, como na 1.18. Em −64 isso é o
  comportamento da 1.18; em −512 as tentativas na superfície ficam ≈ 7× mais raras que na 1.7.10.
* Mods que iteram `getBlockStorageArray()` não veem as seções profundas (por design): scanners, quarries e
  minimapas de outros mods param em Y 0 até ganharem suporte via `BellowAPI`. O Distant Horizons do pack usa altura
  fixa 0..256: as LODs não incluem a camada profunda (invisível da superfície de qualquer forma).
* A sonda testa comportamento de servidor. O visual de máquinas específicas no cliente (TESR) foi validado só pelo
  baú (mesmo caminho de renderização).
* Limites vanilla que ficam como estão (o heightmap nunca desce abaixo de 0, §2): `canBlockSeeTheSky` é falso abaixo
  de 0, então beacons abaixo de 0 não acendem e mortos-vivos não queimam num poço aberto; árvores e cogumelos
  gigantes só crescem com `y >= 1`, cipós param de descer em Y 1; `/setspawn` e `/setworldspawn` aceitam Y 0..256.
* Retrogen de minérios de outros mods que roda fora de `Chunk.populateChunk` (por exemplo a do CoFH, por tick) não
  passa pela guarda de §5 e pode deixar veios antigos abaixo de 0. No Crucible, os ticks aleatórios profundos usam o
  `growthOdds` do Spigot deixado pelo último chunk processado e também tocam chunks na fila de descarregar (o Forge faz
  o mesmo na superfície).
* Pacotes: o orçamento por chunk cobre as 32 seções possíveis (≈ 1 MB), independente do piso, porque o cliente não o
  conhece no registro; o cliente aloca `orçamento × chunks` por S26 (lixo transitório no login e em teleportes, sem
  efeito de corretude). Um S21 forçado de uma coluna com Y 0..255 totalmente vazio descarrega a coluna no cliente,
  camada profunda incluída (só em mundos void).
* A trava do piso é por dimensão no diretório do save: no Crucible cada mundo tem o seu, no Forge é um só; mundos
  com id de dimensão dinâmico (Multiverse) ou movidos entre servidores podem perder a trava. Desde a segunda auditoria
  isso não destrói dados (o que fica abaixo do piso fica dormente), mas a camada some até o piso voltar.
* Fidelidade 1.18: faltam `ore_infested` (montanhas) e as pontas, abaixo de 0, dos veios de terra/granito/diorito/
  andesito/carvão que começam em Y 0..6 (a faixa ≥ 0 é dos geradores 1.7.10). O teleporte de portal varre até o
  piso (≈ 1,25× o custo vanilla em −64, ≈ 3× em −512).
* `MixinWorld_DeepCollision` (Crucible) depende do primeiro `iconst_0` do método; outro mod que injete antes mudaria
  o alvo (o boot não falharia). Correto com o pack atual.
* Numa das ~10 rodadas do teste no Crucible de produção (depois desta auditoria), a thread principal parou durante o
  self-test e o servidor não terminou em 40 min; as 4 rodadas seguintes, com o mesmo jar, passaram. A causa não foi
  determinada (o log foi sobrescrito antes de ser analisado; no ambiente de teste o LuckPerms e o voice chat tentam
  conexões externas que expiram). `tools/crucibletest.sh` agora grava um dump das threads
  (`crucibletest-<fase>-stall.txt`) se o relatório não sair 10 min depois do "Done".
* Conteúdo da camada profunda: fora deste mod, por geradores registrados na API (§5.1; o VitaGenesis). Um servidor sem
  provider fica com a camada provisória (§5) até um ser instalado; com `provisionalLayer = false`, sem camada nos chunks
  novos.
* API 3: `onDecorated` passou a rodar depois do tick do mundo. Um gerador que dependia de rodar dentro da carga do
  chunk precisa se adaptar, e self-tests que carregam chunks fora do laço de ticks (no `FMLServerStartedEvent`)
  precisam chamar `BellowAPI.runDeepStages(world)` antes de conferir callbacks ou luz.
* Downgrade para a beta.2: `L` e `Gens` são ignorados e somem no próximo save do chunk (luz pendente e callbacks
  pendentes daquele chunk não voltam; os blocos ficam).
* Etapa de luz: só abaixo de 0 e só depois de o 5×5 estar decorado. Na borda do que já foi gerado as fontes de luz
  ficam sem luz até os vizinhos serem gerados (o mesmo vale para `onLit`). Luz que deveria subir de Y −1 para Y ≥ 0
  não sobe (o mundo acima de 0 não é tocado).
* Modo estrito: escrita pelo `World` de um ouvinte de `ChunkEvent.Load` de outro mod, num chunk que o gerador fez
  carregar, conta como violação do gerador (é só para teste).
