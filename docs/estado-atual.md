# Estado atual da Carteira

Atualizado em 8 de novembro de 2026.

## Repositório e publicação

- Repositório: `jokasman/carteira`; branch de produção: `main`.
- O fluxo de classificação foi integrado no commit `a05a57c` (`Add movement classification sharing flow`).
- GitHub Pages está ativo a partir de `main` e `/docs`: https://jokasman.github.io/carteira/classificacao/.
- A página estática vive em `docs/classificacao/index.html`.

## Fluxo de classificação e liquidação

1. Em **Movimentos**, o utilizador mantém um movimento premido e escolhe explicitamente os restantes.
2. O pedido de partilha só inclui movimentos selecionados e elegíveis. Para classificar uma despesa, inclui as opções de orçamento disponíveis no mês dessa despesa.
3. Para alterar uma liquidação existente, é necessário selecionar a receita e todas as despesas que já lhe estão associadas.
4. A app abre o seletor de ficheiros do Android para guardar o pedido JSON no dispositivo e depois abre [Classificar e liquidar](https://jokasman.github.io/carteira/classificacao/). O remetente carrega esse ficheiro localmente; a página cria uma ligação.
5. O destinatário abre a ligação, que contém o pedido no fragmento do URL, e decide os orçamentos/liquidações. Não precisa de descarregar ou carregar ficheiros nem de instalar a app.
6. Uma liquidação só é aceite quando o total das despesas selecionadas é exatamente igual ao valor da receita.
7. O destinatário partilha uma segunda ligação com a resposta. O remetente abre-a, descarrega o JSON e, na app, abre **Movimentos → Resposta**, escolhe o ficheiro, revê a pré-visualização e toca em **Aplicar decisões**.

## Limites de privacidade

- O pedido é limitado aos movimentos escolhidos pelo utilizador; não exporta a base de dados, contas bancárias ou notas.
- Para tomar a decisão, o destinatário vê os títulos, datas e valores dos movimentos enviados, além das opções de orçamento pertinentes.
- A página lê o ficheiro do pedido localmente. Os dados seguem no fragmento da ligação, que não é enviado ao servidor da página; a pessoa com a ligação consegue ver os movimentos incluídos.
- O formulário da página processa as ligações localmente. A política CSP bloqueia ligações de rede (`connect-src 'none'`); não existe backend nem armazenamento partilhado.
- A ligação de resposta transporta o ID do pedido e apenas os IDs das decisões; o remetente descarrega o JSON ao abrir essa ligação.
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
- A app permanece nativa Android, com dados locais e sem sincronização de contas.
- Build local da versão `1.2.0` concluído; o JavaScript da página passou a análise sintática e as ligações de pedido/resposta passaram um teste local de ida e volta.
- O site público responde com a página atualizada e o workflow de GitHub Pages confirmou o deploy.
- Não foi feito um teste de ponta a ponta num telemóvel com movimentos reais ou WhatsApp nesta alteração.
- No checkout de trabalho podem existir `.gradle/`, `app/build/` e `build/` não rastreados; não os adicionar ao Git.

## Prompt para continuar noutra conversa

Continua o desenvolvimento da app Android Carteira neste repositório: https://github.com/jokasman/carteira. Lê primeiro `README.md` e `docs/estado-atual.md`; confirma a branch, o estado local e o `origin/main` antes de alterar. Mantém a app nativa, o `applicationId` `pt.jokasman.carteira` e a assinatura atual. A importação bancária continua por CSV; não retomes Enable Banking, Firebase ou backend sem eu pedir. Na função de classificação, só podem ser partilhados os movimentos que eu selecionar; o remetente carrega o pedido localmente na página, que gera uma ligação com o pedido codificado, e o classificador devolve uma segunda ligação com a resposta. Mantém as liquidações pela igualdade exata dos valores. Depois implementa esta alteração: [DESCREVER AQUI]. Se houver um novo APK, usa o workflow GitHub Actions e guarda-o em Ambiente de Trabalho/Carteira APKs.
