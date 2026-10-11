package main

import (
	"context"
	"errors"
	"log/slog"
	"os"
	"path"

	"github.com/aws/aws-lambda-go/lambda"
	"github.com/aws/aws-sdk-go-v2/aws"
	awsconfig "github.com/aws/aws-sdk-go-v2/config"
	"github.com/aws/aws-sdk-go-v2/service/ssm"
	"github.com/bontaramsonta/poof/exit"
)

// tokensPath holds one SecureString per Owner, named by the Owner.
const tokensPath = "/poof/control-plane/tokens"

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	ctx := context.Background()

	cfg, err := awsconfig.LoadDefaultConfig(ctx)
	if err != nil {
		log.Error("loading AWS config", "error", err.Error())
		os.Exit(1)
	}
	// Read once per cold start; token.sh forces a new one after a change.
	tokens, err := loadTokens(ctx, ssm.NewFromConfig(cfg))
	if err != nil {
		log.Error("reading token parameters", "error", err.Error())
		os.Exit(1)
	}

	regions := map[string]Region{}
	for _, r := range exit.AllRegions() {
		regions[r] = exit.NewProvisionerFromConfig(cfg, r)
	}
	h := NewHandler(tokens, func(name string) Region { return regions[name] }, log)
	lambda.Start(h.Handle)
}

// loadTokens maps each Owner to their token. The Owner is the
// parameter's last path segment.
func loadTokens(ctx context.Context, client *ssm.Client) (map[string]string, error) {
	tokens := map[string]string{}
	pages := ssm.NewGetParametersByPathPaginator(client, &ssm.GetParametersByPathInput{
		Path:           aws.String(tokensPath),
		WithDecryption: aws.Bool(true),
	})
	for pages.HasMorePages() {
		out, err := pages.NextPage(ctx)
		if err != nil {
			return nil, err
		}
		for _, p := range out.Parameters {
			tokens[path.Base(aws.ToString(p.Name))] = aws.ToString(p.Value)
		}
	}
	if len(tokens) == 0 {
		return nil, errors.New("no tokens under " + tokensPath)
	}
	return tokens, nil
}
