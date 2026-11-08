# Estado atual da Carteira

Atualizado em 8 de novembro de 2026.

## Repositório e publicação

- Repositório: `jokasman/carteira`; branch de produção: `main`.
- A divisão de despesas na classificação partilhada substitui a divisão de receitas anterior; as partes são movimentos independentes na Carteira e podem voltar a ser divididas.
- GitHub Pages está ativo a partir de `main` e `/docs`: https://jokasman.github.io/carteira/classificacao/.
- A página estática vive em `docs/classificacao/index.html`.
- As ligações antigas com pedidos/respostas embutidos são mantidas em `docs/classificacao/legacy.html` para não interromper classificações já partilhadas.

## Fluxo de classificação e liquidação

1. Em **Movimentos**, o utilizador mantém um movimento premido e escolhe explicitamente os restantes.
2. O pedido de partilha só inclui movimentos selecionados e elegíveis. Para classificar uma despesa, inclui as opções de orçamento disponíveis no mês dessa despesa.
3. Para alterar uma liquidação existente, é necessário selecionar a receita e todas as despesas que já lhe estão associadas.
4. A app abre o seletor de ficheiros do Android para guardar o pedido JSON no dispositivo e depois abre a página de classificação no GitHub Pages. O remetente carrega o JSON na página; esta envia-o à API Worker e gera um link curto.
5. O destinatário abre o link e decide os orçamentos/liquidações. Também pode dividir uma despesa ativa em duas partes, atribuir nome, valor e orçamento opcional a cada parte. Não precisa de descarregar ou carregar ficheiros nem de instalar a app.
6. A soma das partes da despesa tem de ser exatamente igual ao movimento original. Cada parte sem orçamento aparece separadamente na lista de liquidação; as partes com orçamento ficam fora dessa lista. É possível guardar só a divisão. A igualdade exata das liquidações continua a ser validada em cêntimos.
7. O destinatário avisa o remetente quando termina. O remetente abre o mesmo link, descarrega o JSON e, na app, abre **Movimentos → Resposta**, revê as partes e toca em **Aplicar decisões**. A Carteira guarda cada parte como um movimento independente, com o orçamento e a liquidação que lhe correspondem; esses movimentos podem voltar a ser divididos na app.

## Limites de privacidade

- O pedido é limitado aos movimentos escolhidos pelo utilizador; não exporta a base de dados, contas bancárias ou notas.
- Para tomar a decisão, o destinatário vê os títulos, datas e valores dos movimentos enviados, além das opções de orçamento pertinentes.
- O pedido e a resposta ficam no Cloudflare D1; o link expira após 30 dias e os dados expirados são limpos em chamadas seguintes à API. O link contém um token aleatório; qualquer pessoa com esse link consegue consultar os movimentos e a resposta.
- O token fica no fragmento do link e no corpo dos pedidos à API, nunca na query string. O Worker guarda o token como hash. A API limita novas tarefas a 10 por endereço de rede por dia e limpa hashes dos dias anteriores em chamadas à API.
- O pedido original fica na área privada da app por até 30 dias, é excluído das cópias de segurança Android e é apagado após importação bem-sucedida.
- A app volta a validar o pedido, o estado atual dos movimentos, a disponibilidade dos orçamentos, os valores e nomes das partes e a igualdade exata dos totais. Cria as partes e liga cada liquidação às parcelas selecionadas na mesma transação local.

## Android e entrega de APK

- `applicationId`: `pt.jokasman.carteira`. Não o alteres para futuras atualizações.
- APK atual: versão `1.2.5` (versionCode `8`), pacote `pt.jokasman.carteira`, criado pelo run GitHub Actions `42508618351` a partir do commit `e9bf39a96f6fc02370c5cf8f4f963377cb61b265` (artefacto `Carteira-APK`, disponível até 6 de fevereiro de 2027). Inclui a divisão de despesas e a liquidação por partes. A versão `1.2.4` (run `42505469468`) é anterior e não liquida partes individualmente. Ainda não foi confirmado o SHA-256 do `.apk` nem a cópia para **Carteira APKs**.
- O certificado SHA-256 da assinatura (`43af88f1f82380dbc2d22205dbe44a8e8013dec2f2750ac1bfb995422708f83e`) coincide com o APK anterior; `applicationId` e assinatura estável foram mantidos.
- SHA-256 do APK: `B66D8F413CA1B7DF7E62F0FA39E69E06F66C780B78778F49B5E2A42E85293138`. Guardado em **Ambiente de Trabalho/Carteira APKs**. Regra do utilizador: guardar aí todos os próximos APKs.
- O build Android terminou com sucesso no run `42505469468`; o GitHub Pages foi publicado no run `42505469619` e serve a classificação em `https://jokasman.github.io/carteira/classificacao/`.
- Para futuros APKs: fazer push autorizado para `main`, esperar pelo workflow **Build Android APK**, descarregar o artefacto `Carteira-APK`, confirmar package/signature e copiar o `.apk` para **Carteira APKs** no Ambiente de Trabalho. Não adicionar APKs ao Git.

## Decisões e verificação

- A importação bancária continua a usar CSV. O utilizador cancelou Enable Banking/Firebase e pediu para reverter essa integração; não retomar backend local nem cloud sem um pedido explícito.
- Uma despesa pode ser guardada sem orçamento, editada e dividida sem associação obrigatória. Orçamentos continuam opcionais e podem ser associados depois.
- A app permanece nativa Android, com dados locais e sem sincronização de contas. O remetente carrega o pedido e descarrega a resposta JSON; o classificador trabalha no link sem ficheiros.
- O Worker é publicado automaticamente pelo workflow `.github/workflows/deploy-worker.yml` (push para `main` que altere `cloudflare/classification-api/` ou `docs/`, ou execução manual em Actions), com os secrets de repositório `CLOUDFLARE_API_TOKEN` e `CLOUDFLARE_ACCOUNT_ID`. O workflow usa `wranglerVersion: "4"`; versões antigas do Wrangler não leem `wrangler.jsonc`. Última publicação com sucesso: run `42525358597` (8 de novembro de 2026, 21:28 UTC), a partir do commit `97104d7`, que inclui a validação de partes liquidadas.
- O Worker e a D1 estão publicados na conta Cloudflare reclamada; o GitHub Pages serve a interface e chama a API do Worker. A migração D1 está aplicada.
- O Wrangler local não está autenticado na conta Cloudflare; não é necessário para publicar, porque o deploy corre no GitHub Actions. A migração D1 não é aplicada pelo workflow e continua manual (`wrangler d1 migrations apply --remote`).
- O GitHub Pages publica `docs/` a partir de `main`; `docs/classificacao/config.js` define a origem Worker da API.
- A página e o Worker validam as escolhas e cada liquidação por igualdade exata em cêntimos; a página e a Carteira validam novamente as partes das despesas divididas antes de importar. O Worker aceita uma única resposta por tarefa.
- Não foi feito um teste de ponta a ponta num telemóvel com movimentos reais ou WhatsApp nesta alteração.
- No checkout de trabalho podem existir `.gradle/`, `app/build/` e `build/` não rastreados; não os adicionar ao Git.

## Prompt para continuar noutra conversa

Continua o desenvolvimento da app Android Carteira neste repositório: https://github.com/jokasman/carteira. Lê primeiro `README.md` e `docs/estado-atual.md`; confirma a branch, o estado local e o `origin/main` antes de alterar. Mantém o `applicationId` `pt.jokasman.carteira` e a assinatura atual. A importação bancária continua por CSV; não retomes Enable Banking ou Firebase. A classificação partilhada usa GitHub Pages + API Cloudflare Worker/D1, com retenção de 30 dias: o remetente carrega o pedido JSON na página e envia o link; o classificador não troca ficheiros; o remetente abre o mesmo link e descarrega o JSON de resposta para importar. Só podem ser partilhados os movimentos explicitamente selecionados. No link, cada despesa pode ser dividida em duas partes com total exato e orçamento opcional por parte; parcelas sem orçamento podem ser liquidadas individualmente. A Carteira grava cada parcela como movimento normal, passível de nova divisão. O Worker Cloudflare e a D1 estão ativos na conta reclamada; o GitHub Pages serve a interface e chama a API. Se houver um APK novo, guarda-o em Ambiente de Trabalho/Carteira APKs e não adiciones APKs nem pastas de build ao Git.
