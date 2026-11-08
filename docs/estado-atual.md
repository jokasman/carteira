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
4. O remetente partilha o pedido JSON pelo menu nativo do Android, por exemplo com WhatsApp.
5. O destinatário abre o formulário web, escolhe o ficheiro recebido e decide os orçamentos/liquidações. O destinatário não precisa da app.
6. Uma liquidação só é aceite quando o total das despesas selecionadas é exatamente igual ao valor da receita.
7. O destinatário partilha a resposta JSON. O remetente abre **Movimentos → Resposta**, escolhe o ficheiro, revê a pré-visualização e toca em **Aplicar decisões**.

## Limites de privacidade

- O pedido é limitado aos movimentos escolhidos pelo utilizador; não exporta a base de dados, contas bancárias ou notas.
- Para tomar a decisão, o destinatário vê os títulos, datas e valores dos movimentos enviados, além das opções de orçamento pertinentes.
- A página trata o JSON localmente no browser. A política CSP bloqueia ligações de rede (`connect-src 'none'`); não existe backend nem upload dos movimentos.
- A resposta transporta o ID do pedido e apenas os IDs das decisões.
- O pedido original fica na área privada da app por até 30 dias, é excluído das cópias de segurança Android e é apagado após importação bem-sucedida.
- A app volta a validar o pedido, o estado atual dos movimentos, a disponibilidade do orçamento e a igualdade exata dos totais. As alterações à base de dados são aplicadas em transação.

## Android e entrega de APK

- `applicationId`: `pt.jokasman.carteira`. Não o alteres para futuras atualizações.
- APK mais recente: `Carteira-APK-42473137427.apk`, criado pelo run GitHub Actions `42473137427` a partir do commit `a05a57c`; pacote `pt.jokasman.carteira`, versão `1.1.0` (versionCode `2`).
- A assinatura do APK foi comparada com a do APK anterior e coincide.
- O ficheiro foi guardado na pasta OneDrive **Ambiente de Trabalho/Carteira APKs**. Regra do utilizador: guardar aí todos os próximos APKs.
- O build automático Android terminou com sucesso. O deploy de GitHub Pages a partir de `main` terminou com sucesso no run `42473305688`.
- Para futuros APKs: fazer push autorizado para `main`, esperar pelo workflow **Build Android APK**, descarregar o artefacto `Carteira-APK`, confirmar package/signature e copiar o `.apk` para **Carteira APKs** no Ambiente de Trabalho. Não adicionar APKs ao Git.

## Decisões e verificação

- A importação bancária continua a usar CSV. O utilizador cancelou Enable Banking/Firebase e pediu para reverter essa integração; não retomar backend local nem cloud sem um pedido explícito.
- A app permanece nativa Android, com dados locais e sem sincronização de contas.
- Confirmado: build Android CI, deploy do site e carregamento público do formulário.
- Não foi feito um teste de ponta a ponta num telemóvel com movimentos reais ou WhatsApp nesta alteração.
- No checkout de trabalho podem existir `.gradle/`, `app/build/` e `build/` não rastreados; não os adicionar ao Git.

## Prompt para continuar noutra conversa

Continua o desenvolvimento da app Android Carteira neste repositório: https://github.com/jokasman/carteira. Lê primeiro `README.md` e `docs/estado-atual.md`; confirma a branch, o estado local e o `origin/main` antes de alterar. Mantém a app nativa, o `applicationId` `pt.jokasman.carteira` e a assinatura atual. A importação bancária continua por CSV; não retomes Enable Banking, Firebase ou backend sem eu pedir. Na função de classificação, só podem ser partilhados os movimentos que eu selecionar; mantém a página a processar o ficheiro localmente e valida as liquidações pela igualdade exata dos valores. Depois implementa esta alteração: [DESCREVER AQUI]. Se houver um novo APK, usa o workflow GitHub Actions e guarda-o em Ambiente de Trabalho/Carteira APKs.
