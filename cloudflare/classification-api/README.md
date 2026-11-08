# API temporária de classificação

O GitHub Pages serve a página em `docs/` e esta chama a API do Worker Cloudflare. O D1 guarda o pedido e, quando enviado, a resposta. A página não coloca os movimentos no link: este contém um token aleatório de 256 bits, guardado como hash na base de dados.

## Publicação atual

O Worker e a D1 estão publicados na conta Cloudflare reclamada. A API está em `https://carteira-classificacao.faint-foxtail.workers.dev/api/`; a página de classificação está em `https://jokasman.github.io/carteira/classificacao/`. O Worker permite pedidos CORS da origem GitHub Pages. O Wrangler local não está autenticado nessa conta; futuras alterações ao Worker devem usar `wrangler login` ou um API Token limitado à conta.

Os links expiram após 30 dias. Depois de cada chamada à API, o Worker apaga pedidos expirados e hashes de limite antigos, sem depender de tarefas Cron. A criação está limitada a 10 pedidos por endereço de rede por dia; a base de dados guarda apenas um hash diário desse endereço para aplicar o limite.

## Configuração numa conta Cloudflare permanente

Na pasta `cloudflare/classification-api`:

1. Cria um API Token limitado à conta, com `Workers Scripts: Edit/Write`, `D1: Edit/Write` e `Account Settings: Read`.
2. Copia `.env.example` para `.env` e preenche o ID da conta e o token. O `.env` está ignorado pelo Git; não envies o token por mensagem nem o comites.
3. Cria a base de dados com `npx wrangler d1 create carteira-classificacao`.
4. Copia o `database_id` devolvido para `wrangler.jsonc`, substituindo o valor de exemplo.
5. Aplica a migração remota com `npx wrangler d1 migrations apply carteira-classificacao --remote`.
6. Publica com `npx wrangler deploy` e anota o endereço `*.workers.dev` devolvido.
7. Coloca o endereço do Worker, terminado em `/classificacao/`, em `window.CARTEIRA_CLASSIFICATION_APP_URL` no ficheiro `docs/classificacao/config.js`; a página usa esse endereço para chamar a API.
8. Publica o Worker e envia as alterações de página para `main`; o GitHub Pages publica a interface e o Worker mantém a API e a base de dados.

Não publiques um `database_id` de exemplo. O endereço da aplicação não é secreto; os tokens dos pedidos nunca devem ser registados nos logs.

## API

- `POST /api/create` recebe e valida o pedido JSON; responde com um token aleatório.
- `POST /api/load` recebe o token no corpo e devolve o pedido e, se existir, a resposta.
- `POST /api/submit` recebe o token e a resposta. O Worker verifica se as decisões pertencem ao pedido e se cada liquidação soma exatamente o valor da receita. A resposta pode incluir partes de despesa; a página e a Carteira validam a divisão e a app aplica as partes localmente. Só é aceite uma resposta por pedido.

As chamadas não guardam o token na query string. A página coloca-o apenas no fragmento do link, que não é enviado numa navegação HTTP normal.
