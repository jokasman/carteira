# Estado atual da Carteira

Atualizado em 8 de novembro de 2026.

## Repositório e publicação

- Repositório: `jokasman/carteira`; branch de produção: `main`.
- O fluxo de classificação foi integrado no commit `a05a57c` (`Add movement classification sharing flow`).
- GitHub Pages está ativo a partir de `main` e `/docs`: https://jokasman.github.io/carteira/classificacao/.
- A página estática vive em `docs/classificacao/index.html`.
- As ligações antigas com pedidos/respostas embutidos são mantidas em `docs/classificacao/legacy.html` para não interromper classificações já partilhadas.

## Fluxo de classificação e liquidação

1. Em **Movimentos**, o utilizador mantém um movimento premido e escolhe explicitamente os restantes.
2. O pedido de partilha só inclui movimentos selecionados e elegíveis. Para classificar uma despesa, inclui as opções de orçamento disponíveis no mês dessa despesa.
3. Para alterar uma liquidação existente, é necessário selecionar a receita e todas as despesas que já lhe estão associadas.
4. A app abre o seletor de ficheiros do Android para guardar o pedido JSON no dispositivo e depois abre a página de classificação. O remetente carrega o JSON na página; o Worker guarda-o temporariamente no D1 e gera um link curto.
5. O destinatário abre o link e decide os orçamentos/liquidações. Não precisa de descarregar ou carregar ficheiros nem de instalar a app.
6. Uma liquidação só é aceite quando o total das despesas selecionadas é exatamente igual ao valor da receita.
7. O destinatário avisa o remetente quando termina. O remetente abre o mesmo link, descarrega o JSON e, na app, abre **Movimentos → Resposta**, escolhe o ficheiro, revê a pré-visualização e toca em **Aplicar decisões**.

## Limites de privacidade

- O pedido é limitado aos movimentos escolhidos pelo utilizador; não exporta a base de dados, contas bancárias ou notas.
- Para tomar a decisão, o destinatário vê os títulos, datas e valores dos movimentos enviados, além das opções de orçamento pertinentes.
- O pedido e a resposta ficam no Cloudflare D1; o link expira após 30 dias e os dados expirados são limpos em chamadas seguintes à API. O link contém um token aleatório; qualquer pessoa com esse link consegue consultar os movimentos e a resposta.
- O token fica no fragmento do link e no corpo dos pedidos à API, nunca na query string. O Worker guarda o token como hash. A API limita novas tarefas a 10 por endereço de rede por dia e limpa hashes dos dias anteriores em chamadas à API.
- O pedido original fica na área privada da app por até 30 dias, é excluído das cópias de segurança Android e é apagado após importação bem-sucedida.
- A app volta a validar o pedido, o estado atual dos movimentos, a disponibilidade do orçamento e a igualdade exata dos totais. As alterações à base de dados são aplicadas em transação.

## Android e entrega de APK

- `applicationId`: `pt.jokasman.carteira`. Não o alteres para futuras atualizações.
- APK desta versão: `Carteira-APK-42480740611.apk`, criado pelo run GitHub Actions `42480740611` a partir do commit `d2a2d95`; pacote `pt.jokasman.carteira`, versão `1.2.0` (versionCode `3`).
- O certificado SHA-256 da assinatura coincide com o APK anterior; o `applicationId` e a assinatura estável foram mantidos.
- O APK foi guardado em **Ambiente de Trabalho/Carteira APKs**. Regra do utilizador: guardar aí todos os próximos APKs.
- O build Android desta versão terminou com sucesso no run `42480740611`; o deploy GitHub Pages terminou com sucesso no run `42480740453`.
- Para futuros APKs: fazer push autorizado para `main`, esperar pelo workflow **Build Android APK**, descarregar o artefacto `Carteira-APK`, confirmar package/signature e copiar o `.apk` para **Carteira APKs** no Ambiente de Trabalho. Não adicionar APKs ao Git.

## Decisões e verificação

- A importação bancária continua a usar CSV. O utilizador cancelou Enable Banking/Firebase e pediu para reverter essa integração; não retomar backend local nem cloud sem um pedido explícito.
- A app permanece nativa Android, com dados locais e sem sincronização de contas. O utilizador continua a carregar o pedido e a descarregar a resposta JSON; não é necessária nova versão Android.
- O Worker e a D1 estão publicados na conta Cloudflare reclamada. A página está disponível em `https://carteira-classificacao.faint-foxtail.workers.dev/classificacao/`; a migração D1 está aplicada e o percurso criar pedido → classificar → descarregar resposta foi confirmado com dados sintéticos.
- O Wrangler local não está autenticado na conta Cloudflare permanente; o Worker ativo continua acessível e futuras alterações ao backend exigirão `wrangler login` ou um API Token limitado à conta.
- O GitHub Pages publica `docs/` a partir de `main` e encaminha a página de classificação para `/classificacao/` no Worker configurado em `docs/classificacao/config.js`.
- A página e o Worker validam as escolhas e cada liquidação por igualdade exata em cêntimos; o Worker aceita uma única resposta por tarefa.
- Não foi feito um teste de ponta a ponta num telemóvel com movimentos reais ou WhatsApp nesta alteração.
- No checkout de trabalho podem existir `.gradle/`, `app/build/` e `build/` não rastreados; não os adicionar ao Git.

## Prompt para continuar noutra conversa

Continua o desenvolvimento da app Android Carteira neste repositório: https://github.com/jokasman/carteira. Lê primeiro `README.md` e `docs/estado-atual.md`; confirma a branch, o estado local e o `origin/main` antes de alterar. Mantém a app nativa, o `applicationId` `pt.jokasman.carteira` e a assinatura atual. A importação bancária continua por CSV; não retomes Enable Banking ou Firebase. A classificação partilhada usa Cloudflare Worker + D1, com retenção de 30 dias: o remetente carrega o pedido JSON na página e envia um link curto; o classificador não troca ficheiros; o remetente abre o mesmo link e descarrega o JSON de resposta para importar. Só podem ser partilhados os movimentos explicitamente selecionados e as liquidações exigem igualdade exata. O Worker Cloudflare e a D1 estão ativos na conta reclamada; o GitHub Pages encaminha para o Worker. Se houver um APK novo, guarda-o em Ambiente de Trabalho/Carteira APKs e não adiciones APKs nem pastas de build ao Git.
