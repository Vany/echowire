#!/usr/bin/env python3
"""
Extract full Whisper vocabulary (base + added tokens) from HuggingFace tokenizer.json
"""
import json
import sys

def extract_whisper_vocab(tokenizer_file, output_file):
    """Extract vocabulary with added tokens"""
    with open(tokenizer_file, 'r') as f:
        tokenizer = json.load(f)
    
    # Start with base vocabulary (token -> id)
    base_vocab = tokenizer['model']['vocab']
    print(f"Base vocab size: {len(base_vocab)}")
    
    # Invert to id -> token
    vocab = {str(v): k for k, v in base_vocab.items()}
    
    # Add special tokens from added_tokens
    added_tokens = tokenizer.get('added_tokens', [])
    print(f"Added tokens: {len(added_tokens)}")
    
    for token_info in added_tokens:
        token_id = str(token_info['id'])
        token_content = token_info['content']
        vocab[token_id] = token_content
        if int(token_id) < 50270:  # Show first few language tokens
            print(f"  {token_id}: {token_content}")
    
    print(f"\nTotal vocab size: {len(vocab)}")
    print(f"Max token ID: {max(int(k) for k in vocab.keys())}")
    
    # Write to output file
    with open(output_file, 'w') as f:
        # Sort by numeric ID for readability
        sorted_vocab = {k: vocab[k] for k in sorted(vocab.keys(), key=int)}
        json.dump(sorted_vocab, f, indent=2, ensure_ascii=False)
    
    print(f"\nWrote vocabulary to: {output_file}")
    
    # Verify some key tokens
    print("\nVerification:")
    print(f"  Token 50257 (<|endoftext|>): {vocab.get('50257', 'MISSING')}")
    print(f"  Token 50258 (<|startoftranscript|>): {vocab.get('50258', 'MISSING')}")
    print(f"  Token 50259 (<|en|>): {vocab.get('50259', 'MISSING')}")
    print(f"  Token 50263 (<|ru|>): {vocab.get('50263', 'MISSING')}")
    print(f"  Token 50265 (<|fr|>): {vocab.get('50265', 'MISSING')}")
    print(f"  Token 50304: {vocab.get('50304', 'MISSING')}")
    print(f"  Token 50363 (<|notimestamps|>): {vocab.get('50363', 'MISSING')}")

if __name__ == '__main__':
    if len(sys.argv) != 3:
        print("Usage: python3 extract_whisper_vocab.py <tokenizer.json> <output_vocab.json>")
        sys.exit(1)
    
    extract_whisper_vocab(sys.argv[1], sys.argv[2])
